package ru.studhub.hungergames.arena;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import ru.studhub.hungergames.config.HgSettings;
import ru.studhub.hungergames.loot.LootPool;
import ru.studhub.hungergames.player.Position;
import ru.studhub.hungergames.storage.JsonFiles;

public final class ArenaService {
    private static final TicketType<ChunkPos> SCAN_TICKET = TicketType.create("hungergames_scan", Comparator.comparingLong(ChunkPos::toLong));
    private static final int ENTITY_LOAD_TIMEOUT_TICKS = 1200;
    private final MinecraftServer server;
    private HgSettings settings;
    private ServerLevel level;
    private final List<ChunkPos> scanChunks = new ArrayList<>();
    private final Set<ChunkPos> tickets = new HashSet<>();
    private final Set<BlockPos> chests = new LinkedHashSet<>();
    private final Map<UUID, HgSettings.Corner> minecartChests = new LinkedHashMap<>();
    private final Map<ChunkPos, Integer> pendingChunks = new LinkedHashMap<>();
    private int cursor;
    private int scanTicks;
    private boolean migrating;
    private record CartConversion(UUID uuid, HgSettings.Corner from, HgSettings.Corner to) {}
    private final List<CartConversion> conversions = new ArrayList<>();
    private Consumer<String> completion;
    private LootPool fillPool;
    private RandomGenerator random;

    public ArenaService(MinecraftServer server) { this.server = server; }

    public ServerLevel level(HgSettings settings) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(settings.arenaDimension)));
    }

    public Position center(HgSettings settings, float yaw, float pitch) {
        ServerLevel arena = level(settings);
        if (arena == null) throw new IllegalStateException("Измерение арены не загружено: " + settings.arenaDimension);
        double x = settings.border.centerX, z = settings.border.centerZ;
        BlockPos column = BlockPos.containing(x, 0, z);
        arena.getChunkAt(column);
        // Prefer the playing surface to the roof or lobby platform above the same column.
        double preferredY = settings.spawns.values().stream().filter(p -> p.dimension().equals(settings.arenaDimension))
                .min(Comparator.comparingDouble(p -> Math.pow(p.x() - x, 2) + Math.pow(p.z() - z, 2)))
                .map(Position::y).orElseGet(() -> (double) arena.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()));
        Position best = null;
        for (int y = arena.getMinBuildHeight() + 1; y + 1.8 < arena.getMaxBuildHeight(); y++) {
            BlockPos feet = new BlockPos(column.getX(), y, column.getZ()), floor = feet.below();
            var support = arena.getBlockState(floor);
            if (support.getCollisionShape(arena, floor).isEmpty() || support.is(Blocks.MAGMA_BLOCK)
                    || support.is(Blocks.CACTUS) || support.is(Blocks.CAMPFIRE) || support.is(Blocks.SOUL_CAMPFIRE)
                    || !arena.getFluidState(feet).isEmpty() || !arena.getFluidState(feet.above()).isEmpty()
                    || arena.getBlockState(feet).is(BlockTags.FIRE) || arena.getBlockState(feet).is(Blocks.POWDER_SNOW)
                    || arena.getBlockState(feet).is(Blocks.SWEET_BERRY_BUSH)
                    || !arena.noCollision(new AABB(x - .3, y, z - .3, x + .3, y + 1.8, z + .3))) continue;
            if (best == null || Math.abs(y - preferredY) < Math.abs(best.y() - preferredY)) {
                best = new Position(settings.arenaDimension, x, y, z, yaw, pitch);
            }
        }
        if (best == null) throw new IllegalStateException("В центре арены нет безопасного места для появления.");
        return best;
    }

    public void validate(HgSettings settings, int players) {
        ServerLevel arena = level(settings);
        if (arena == null) throw new IllegalStateException("Измерение арены не загружено: " + settings.arenaDimension);
        if (settings.lobby == null || !settings.lobby.dimension().equals(settings.arenaDimension)) {
            throw new IllegalStateException("Задайте точку ожидания в измерении арены: /hg set_lobby");
        }
        if (settings.corner1 == null || settings.corner2 == null) throw new IllegalStateException("Задайте область: /hg arena pos1 и /hg arena pos2");
        validatePosition(arena, settings.lobby, "Точка ожидания");
        long chunks = ((long) (maxX(settings) >> 4) - (minX(settings) >> 4) + 1)
                * ((long) (maxZ(settings) >> 4) - (minZ(settings) >> 4) + 1);
        if (chunks < 1 || chunks > settings.maxArenaChunks) throw new IllegalStateException("Слишком большая область арены: " + chunks + " чанков");
        for (int slot = 1; slot <= players; slot++) {
            Position position = settings.spawns.get(slot);
            if (position == null || !position.dimension().equals(settings.arenaDimension) || !contains(settings, position.block())) {
                throw new IllegalStateException("Некорректная точка появления №" + slot + ": /hg set_spawn " + slot);
            }
            if (Math.abs(position.x() - settings.border.centerX) >= settings.border.initialSize / 2
                    || Math.abs(position.z() - settings.border.centerZ) >= settings.border.initialSize / 2) {
                throw new IllegalStateException("Точка №" + slot + " за начальной границей");
            }
            validatePosition(arena, position, "Точка №" + slot);
        }
    }

    private static void validatePosition(ServerLevel level, Position position, String name) {
        level.getChunkAt(position.block());
        BlockPos floor = BlockPos.containing(position.x(), position.y() - .1, position.z());
        if (position.y() < level.getMinBuildHeight() + 1 || position.y() + 1.8 >= level.getMaxBuildHeight()
                || !level.noCollision(new AABB(position.x() - .3, position.y(), position.z() - .3,
                position.x() + .3, position.y() + 1.8, position.z() + .3))
                || level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) {
            throw new IllegalStateException(name + " должна находиться на опоре в свободном пространстве");
        }
    }

    public void scan(HgSettings settings, LootPool pool, RandomGenerator random, Consumer<String> completion) {
        if (busy()) throw new IllegalStateException("Сканирование уже идёт");
        validate(settings, 0);
        release();
        this.settings = settings;
        this.level = level(settings);
        this.fillPool = pool;
        this.random = random;
        this.completion = completion;
        chests.clear();
        minecartChests.clear();
        scanTicks = 0;
        migrating = false;
        conversions.clear();
        scanChunks.clear();
        cursor = 0;
        for (int x = minX(settings) >> 4; x <= maxX(settings) >> 4; x++) {
            for (int z = minZ(settings) >> 4; z <= maxZ(settings) >> 4; z++) scanChunks.add(new ChunkPos(x, z));
        }
    }

    public void migrateContainers(HgSettings settings, Consumer<String> completion) {
        scan(settings, null, RandomGenerator.getDefault(), completion);
        migrating = true;
    }

    public void tick() {
        if (!busy()) return;
        try {
            scanTicks++;
            int processed = processPendingChunks(settings.chunksPerTick);
            for (int i = 0; i < settings.chunksPerTick && tickets.size() < settings.chunksPerTick && cursor < scanChunks.size(); i++) {
                ChunkPos pos = scanChunks.get(cursor++);
                level.getChunkSource().addRegionTicket(SCAN_TICKET, pos, 2, pos);
                tickets.add(pos);
                level.getChunk(pos.x, pos.z);
                pendingChunks.put(pos, scanTicks);
            }
            processPendingChunks(settings.chunksPerTick - processed);
            if (cursor == scanChunks.size() && pendingChunks.isEmpty()) finish(null);
        } catch (Exception e) {
            if (completion != null) finish("Ошибка подготовки арены: " + e.getMessage());
            else throw new IllegalStateException("Ошибка завершения подготовки арены", e);
        }
    }

    private int processPendingChunks(int limit) {
        int processed = 0;
        for (var iterator = pendingChunks.entrySet().iterator(); iterator.hasNext() && processed < limit;) {
            var entry = iterator.next();
            ChunkPos pos = entry.getKey();
            // Entity NBT arrives asynchronously. Hold only this small batch until it is ready.
            if (!level.areEntitiesLoaded(pos.toLong())) {
                if (scanTicks - entry.getValue() > ENTITY_LOAD_TIMEOUT_TICKS) {
                    throw new IllegalStateException("Не удалось дождаться загрузки сущностей чанка " + pos);
                }
                continue;
            }
            processChunk(pos);
            iterator.remove();
            releaseChunk(pos);
            processed++;
        }
        return processed;
    }

    private void processChunk(ChunkPos pos) {
        LevelChunk chunk = level.getChunk(pos.x, pos.z);
        for (BlockPos block : List.copyOf(chunk.getBlockEntitiesPos())) {
            if (!contains(settings, block)) continue;
            if (!allowed(level, block, settings)) continue;
            if (level.getBlockState(block).getBlock() instanceof ChestBlock
                    && level.getBlockState(block).getValue(ChestBlock.TYPE) == ChestType.RIGHT) continue;
            chests.add(block.immutable());
            if (fillPool != null) fill(block);
        }
        AABB bounds = new AABB(pos.getMinBlockX(), level.getMinBuildHeight(), pos.getMinBlockZ(),
                pos.getMaxBlockX() + 1, level.getMaxBuildHeight(), pos.getMaxBlockZ() + 1);
        List<MinecartChest> carts = new ArrayList<>();
        for (MinecartChest cart : level.getEntitiesOfClass(MinecartChest.class, bounds)) {
            BlockPos block = cart.blockPosition();
            if (contains(settings, block) && minecartChests.putIfAbsent(cart.getUUID(),
                    new HgSettings.Corner(block.getX(), block.getY(), block.getZ())) == null) {
                if (migrating) carts.add(cart);
                else if (fillPool != null) fill(cart);
            }
        }
        if (fillPool != null) {
            for (Entity entity : level.getEntities((Entity) null, bounds,
                    e -> e instanceof ItemEntity || e instanceof Projectile || e instanceof net.minecraft.world.entity.item.PrimedTnt)) {
                if (contains(settings, entity.blockPosition())) entity.discard();
            }
        }
        if (migrating) convertContainers(carts);
    }

    private void fill(BlockPos pos) {
        fill(level, pos, fillPool, random);
    }

    public static void fill(ServerLevel level, BlockPos pos, LootPool pool, RandomGenerator random) {
        Container container = container(level, pos);
        IItemHandler handler = container == null ? level.getCapability(Capabilities.ItemHandler.BLOCK, pos, (Direction) null) : null;
        int size = container != null ? container.getContainerSize() : handler == null ? 0 : handler.getSlots();
        if (size < 1) throw new IllegalStateException("Нет доступа к сундуку " + pos.toShortString());
        List<ItemStack> loot = pool.roll(random);
        // Validate the complete selection before clearing; never truncate a weapon's ammunition bundle.
        if (loot.size() > size) throw new IllegalStateException("Слишком много предметов для сундука " + pos);
        if (level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity randomizable) randomizable.setLootTable(null);
        var state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
            if (level.getBlockEntity(partner) instanceof RandomizableContainerBlockEntity randomizable) randomizable.setLootTable(null);
        }
        if (container != null) container.clearContent();
        else {
            for (int slot = 0; slot < size; slot++) {
                ItemStack extracted = handler.extractItem(slot, Integer.MAX_VALUE, false);
                if (!handler.getStackInSlot(slot).isEmpty()) throw new IllegalStateException("Не удалось очистить сундук " + pos);
            }
        }
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < size; slot++) slots.add(slot);
        // Fisher–Yates using the same event RNG as the weighted rolls.
        for (int i = slots.size() - 1; i > 0; i--) Collections.swap(slots, i, random.nextInt(i + 1));
        for (int i = 0; i < loot.size(); i++) {
            if (container != null) container.setItem(slots.get(i), loot.get(i));
            else if (!handler.insertItem(slots.get(i), loot.get(i), false).isEmpty()) throw new IllegalStateException("Сундук отклонил предмет: " + pos);
        }
        if (container != null) container.setChanged();
    }

    private void fill(MinecartChest cart) {
        List<ItemStack> loot = fillPool.roll(random);
        int size = cart.getContainerSize();
        if (loot.size() > size) throw new IllegalStateException("Слишком много предметов для вагонетки " + cart.getUUID());
        cart.setLootTable(null);
        cart.setLootTableSeed(0);
        cart.clearContent();
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < size; slot++) slots.add(slot);
        for (int i = slots.size() - 1; i > 0; i--) Collections.swap(slots, i, random.nextInt(i + 1));
        for (int i = 0; i < loot.size(); i++) cart.setItem(slots.get(i), loot.get(i));
        cart.setChanged();
    }

    private void finish(String error) {
        Consumer<String> callback = completion;
        completion = null;
        if (error == null) {
            try {
                if (migrating) {
                    minecartChests.clear();
                    JsonFiles.write(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                            .resolve("data/hungergames/container_migration.json"), Map.of("converted", conversions, "emptiedBarrels", 0));
                }
                JsonFiles.write(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("data/hungergames/chests.json"), chests.stream().map(p -> new HgSettings.Corner(p.getX(), p.getY(), p.getZ())).toList());
                JsonFiles.write(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("data/hungergames/chest_minecarts.json"), minecartChests);
            } catch (IOException | RuntimeException e) { error = "Не удалось подготовить контейнеры: " + e.getMessage(); }
        }
        if (error == null && fillPool != null && chestCount() == 0) error = "В области арены нет разрешённых сундуков";
        release();
        callback.accept(error);
    }

    private void releaseChunk(ChunkPos pos) {
        level.getChunkSource().removeRegionTicket(SCAN_TICKET, pos, 2, pos);
        tickets.remove(pos);
    }

    public void release() {
        completion = null;
        for (ChunkPos pos : List.copyOf(tickets)) releaseChunk(pos);
        pendingChunks.clear();
        scanChunks.clear();
    }

    public boolean busy() { return completion != null; }
    public int chestCount() { return chests.size() + minecartChests.size(); }

    public static Container container(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chest) return ChestBlock.getContainer(chest, state, level, pos, true);
        return level.getBlockEntity(pos) instanceof Container container ? container : null;
    }

    public static boolean allowed(ServerLevel level, BlockPos pos, HgSettings settings) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        return settings.containerBlocks.stream().anyMatch(value -> value.equals(id.toString())
                || value.equals(id.getNamespace() + ":*"));
    }

    private void convertContainers(List<MinecartChest> carts) {
        Map<MinecartChest, BlockPos> plan = new LinkedHashMap<>();
        Set<BlockPos> reserved = new HashSet<>();
        // Resolve the current chunk's destinations before changing it; release it after conversion.
        for (MinecartChest cart : carts) {
            BlockPos origin = cart.blockPosition();
            BlockPos destination = null;
            search: for (int radius = 0; radius <= 3; radius++) for (int dy : new int[] {0, 1, -1, 2, -2}) {
                for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    BlockPos pos = origin.offset(dx, dy, dz);
                    var state = level.getBlockState(pos);
                    if (contains(settings, pos) && !reserved.contains(pos) && level.getBlockEntity(pos) == null
                            && (state.canBeReplaced() || state.is(BlockTags.RAILS))
                            && !level.getBlockState(pos.above()).isRedstoneConductor(level, pos.above())) {
                        destination = pos.immutable(); break search;
                    }
                }
            }
            if (destination == null) throw new IllegalStateException("Нет места для сундука рядом с вагонеткой " + cart.getUUID());
            reserved.add(destination);
            plan.put(cart, destination);
        }
        for (var entry : plan.entrySet()) {
            MinecartChest cart = entry.getKey();
            BlockPos pos = entry.getValue(), from = cart.blockPosition();
            List<ItemStack> contents = new ArrayList<>();
            for (int slot = 0; slot < cart.getContainerSize(); slot++) contents.add(cart.getItem(slot).copy());
            if (!level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, cart.getDirection()), 3)
                    || !(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
                throw new IllegalStateException("Не удалось установить сундук: " + pos);
            }
            chest.setLootTable(null);
            for (int slot = 0; slot < contents.size(); slot++) chest.setItem(slot, contents.get(slot));
            chest.setChanged();
            cart.setLootTable(null);
            cart.clearContent();
            cart.discard();
            chests.add(pos);
            conversions.add(new CartConversion(cart.getUUID(), new HgSettings.Corner(from.getX(), from.getY(), from.getZ()),
                    new HgSettings.Corner(pos.getX(), pos.getY(), pos.getZ())));
        }
    }

    public static boolean contains(HgSettings settings, BlockPos pos) {
        return settings.corner1 != null && settings.corner2 != null && pos.getX() >= minX(settings) && pos.getX() <= maxX(settings)
                && pos.getZ() >= minZ(settings) && pos.getZ() <= maxZ(settings)
                && pos.getY() >= Math.min(settings.corner1.y(), settings.corner2.y())
                && pos.getY() <= Math.max(settings.corner1.y(), settings.corner2.y());
    }

    private static int minX(HgSettings s) { return Math.min(s.corner1.x(), s.corner2.x()); }
    private static int maxX(HgSettings s) { return Math.max(s.corner1.x(), s.corner2.x()); }
    private static int minZ(HgSettings s) { return Math.min(s.corner1.z(), s.corner2.z()); }
    private static int maxZ(HgSettings s) { return Math.max(s.corner1.z(), s.corner2.z()); }
}
