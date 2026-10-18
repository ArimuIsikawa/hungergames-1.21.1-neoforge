package ru.studhub.hungergames.arena;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.SlotItemHandler;
import ru.studhub.hungergames.config.HgSettings;
import ru.studhub.hungergames.loot.LootPool;
import ru.studhub.hungergames.mixin.ChunkMapAccess;

/** Visits naturally loaded chunks only; chest markers survive unloads and server restarts. */
public final class LazyArenaLoot {
    public static final String READY = "hungergames_loot_ready";
    public static final String OPENED_EVENT = "hungergames_loot_opened_event";
    public static final String ENTITY_EVENT = "hungergames_entity_event";
    private final Consumer<String> failure;
    private ServerLevel level;
    private HgSettings settings;
    private LootPool pool;
    private RandomGenerator random;
    private String eventId;
    private int ticks;
    private int filled;
    private final Map<ChunkPos, Integer> chunks = new LinkedHashMap<>();
    private final ArrayDeque<BlockPos> containers = new ArrayDeque<>();
    private final Set<BlockPos> opened = new LinkedHashSet<>();
    private record PendingOpen(BlockPos pos, int tick) {}
    private final Map<UUID, PendingOpen> pendingOpens = new LinkedHashMap<>();
    private record Parts(BlockPos pos, List<BlockEntity> entities) {}

    public LazyArenaLoot(Consumer<String> failure) { this.failure = failure; }

    public boolean active() { return eventId != null; }
    public int pendingCount() { return chunks.size() + containers.size(); }
    public int filledCount() { return filled; }

    public void begin(ServerLevel level, HgSettings settings, LootPool pool, String eventId, RandomGenerator random) {
        end();
        if (eventId == null || eventId.isEmpty()) throw new IllegalArgumentException("Missing loot event identifier");
        this.level = level;
        this.settings = settings;
        this.pool = pool;
        this.eventId = eventId;
        this.random = random;
        ticks = 0;
        filled = 0;
        // Includes chunks loaded before recruitment or held by an administrator, without loading new ones.
        for (var holder : ((ChunkMapAccess) level.getChunkSource().chunkMap).hungergames$getChunks()) {
            var result = holder.getFullChunkFuture().getNow(null);
            LevelChunk chunk = result == null ? null : result.orElse(null);
            if (chunk != null) loaded(level, chunk.getPos());
        }
    }

    private boolean intersects(ChunkPos pos) {
        return pos.getMaxBlockX() >= Math.min(settings.corner1.x(), settings.corner2.x())
                && pos.getMinBlockX() <= Math.max(settings.corner1.x(), settings.corner2.x())
                && pos.getMaxBlockZ() >= Math.min(settings.corner1.z(), settings.corner2.z())
                && pos.getMinBlockZ() <= Math.max(settings.corner1.z(), settings.corner2.z());
    }

    public void loaded(ServerLevel source, ChunkPos pos) {
        if (active() && source == level && intersects(pos)) chunks.putIfAbsent(pos, ticks);
    }

    public void unloaded(ServerLevel source, ChunkPos pos) {
        if (source == level) chunks.remove(pos);
    }

    public void tick() {
        if (!active()) return;
        ticks++;
        long deadline = System.nanoTime() + settings.lootTimeBudgetMillis * 1_000_000L;
        int chunkCount = 0, containerCount = 0;
        try {
            while (active() && System.nanoTime() < deadline) {
                if (!containers.isEmpty()) {
                    if (containerCount++ >= settings.containersPerTick) break;
                    prepare(containers.removeFirst());
                } else {
                    if (chunkCount++ >= settings.chunksPerTick || chunks.isEmpty()) break;
                    var iterator = chunks.entrySet().iterator();
                    var entry = iterator.next();
                    ChunkPos pos = entry.getKey();
                    int queuedAt = entry.getValue();
                    iterator.remove();
                    LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                    if (chunk == null || !level.areEntitiesLoaded(pos.toLong())) {
                        // Load events can precede FULL status. Retry briefly, without a ticket or a getChunk call.
                        if (ticks - queuedAt < 1200) chunks.put(pos, queuedAt);
                        continue;
                    }
                    containers.addAll(chunk.getBlockEntitiesPos());
                    discardOldEntities(pos);
                }
            }
        } catch (RuntimeException error) { failure.accept("Ошибка заполнения сундуков: " + error.getMessage()); }
    }

    private BlockEntity loadedEntity(BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockEntity(pos);
    }

    private Parts parts(BlockPos pos) {
        BlockEntity entity = loadedEntity(pos);
        if (entity == null || !ArenaService.contains(settings, pos) || !ArenaService.allowed(level, pos, settings)) return null;
        List<BlockEntity> entities = new ArrayList<>(2);
        entities.add(entity);
        var state = entity.getBlockState();
        BlockPos primary = pos;
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos partnerPos = pos.relative(ChestBlock.getConnectedDirection(state));
            BlockEntity partner = loadedEntity(partnerPos);
            if (partner == null || !ArenaService.contains(settings, partnerPos)) return null;
            entities.add(partner);
            if (state.getValue(ChestBlock.TYPE) == ChestType.RIGHT) primary = partnerPos;
        }
        return new Parts(primary, entities);
    }

    public boolean prepare(BlockPos pos) {
        if (!active()) return false;
        Parts parts = parts(pos);
        if (parts == null) return false;
        boolean refill = parts.entities.stream().anyMatch(entity -> {
            var data = entity.getPersistentData();
            String openedEvent = data.getString(OPENED_EVENT);
            return !data.getBoolean(READY) || !openedEvent.isEmpty() && !openedEvent.equals(eventId);
        });
        if (refill) {
            ArenaService.fill(level, parts.pos, pool, random);
            for (BlockEntity entity : parts.entities) {
                entity.getPersistentData().putBoolean(READY, true);
                entity.getPersistentData().remove(OPENED_EVENT);
                entity.setChanged();
            }
            filled++;
        }
        return true;
    }

    public boolean beforeOpen(ServerPlayer player, BlockPos pos) {
        pendingOpens.remove(player.getUUID());
        if (!active() || player.serverLevel() != level || !ArenaService.contains(settings, pos)
                || !ArenaService.allowed(level, pos, settings)) return true;
        if (!prepare(pos)) return false;
        pendingOpens.put(player.getUUID(), new PendingOpen(pos.immutable(), ticks));
        return true;
    }

    public void menuOpened(ServerPlayer player, AbstractContainerMenu menu) {
        if (!active() || player.serverLevel() != level) return;
        PendingOpen pending = pendingOpens.remove(player.getUUID());
        if (pending != null && pending.tick == ticks) {
            BlockEntity entity = loadedEntity(pending.pos);
            if (entity != null && belongsTo(menu, entity)) markOpened(pending.pos);
        }
        // Also cover menus opened directly by another server mod, without a right-click packet.
        Set<BlockPos> direct = new LinkedHashSet<>();
        for (var slot : menu.slots) if (slot.container instanceof BlockEntity entity && entity.getLevel() == level) direct.add(entity.getBlockPos());
        for (BlockPos pos : direct) markOpened(pos);
        menu.broadcastChanges();
    }

    private boolean belongsTo(AbstractContainerMenu menu, BlockEntity entity) {
        var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, entity.getBlockPos(), (net.minecraft.core.Direction) null);
        return menu.slots.stream().anyMatch(slot -> slot.container == entity
                || slot.container instanceof CompoundContainer combined && combined.contains(entity instanceof net.minecraft.world.Container c ? c : null)
                || slot instanceof SlotItemHandler itemSlot && handler != null && itemSlot.getItemHandler() == handler);
    }

    public void markOpened(BlockPos pos) {
        if (!prepare(pos)) return;
        Parts parts = parts(pos);
        for (BlockEntity entity : parts.entities) {
            entity.getPersistentData().putString(OPENED_EVENT, eventId);
            entity.setChanged();
            opened.add(entity.getBlockPos().immutable());
        }
    }

    public void end() {
        if (active()) for (BlockPos pos : opened) {
            BlockEntity entity = loadedEntity(pos);
            if (entity != null && eventId.equals(entity.getPersistentData().getString(OPENED_EVENT))) {
                entity.getPersistentData().putBoolean(READY, false);
                entity.getPersistentData().remove(OPENED_EVENT);
                entity.setChanged();
            }
            // Unloaded chests retain OPENED_EVENT in their chunk. A future event interprets it as false.
        }
        eventId = null;
        chunks.clear();
        containers.clear();
        pendingOpens.clear();
        opened.clear();
    }

    private static boolean transientEntity(Entity entity) {
        return entity instanceof ItemEntity || entity instanceof Projectile || entity instanceof PrimedTnt;
    }

    public boolean entityJoined(Entity entity, boolean fromDisk) {
        if (!active() || entity.level() != level || !ArenaService.contains(settings, entity.blockPosition()) || !transientEntity(entity)) return true;
        if (fromDisk) return eventId.equals(entity.getPersistentData().getString(ENTITY_EVENT));
        entity.getPersistentData().putString(ENTITY_EVENT, eventId);
        return true;
    }

    private void discardOldEntities(ChunkPos pos) {
        AABB bounds = new AABB(pos.getMinBlockX(), level.getMinBuildHeight(), pos.getMinBlockZ(),
                pos.getMaxBlockX() + 1, level.getMaxBuildHeight(), pos.getMaxBlockZ() + 1);
        for (Entity entity : level.getEntities((Entity) null, bounds, LazyArenaLoot::transientEntity)) {
            if (ArenaService.contains(settings, entity.blockPosition())
                    && !eventId.equals(entity.getPersistentData().getString(ENTITY_EVENT))) entity.discard();
        }
    }
}
