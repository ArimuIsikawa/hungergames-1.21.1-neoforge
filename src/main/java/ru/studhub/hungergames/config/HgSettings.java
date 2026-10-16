package ru.studhub.hungergames.config;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import ru.studhub.hungergames.player.Position;

public final class HgSettings {
    public int schemaVersion = 1;
    public String scheduleTime = "20:00";
    public String scheduleZone = "Europe/Moscow";
    public int registrationSeconds = 900;
    public int minPlayers = 4;
    public int maxPlayers = 8;
    public int countdownSeconds = 180;
    public int fullCountdownSeconds = 15;
    public int maxMatchSeconds = 3600;
    public int chunksPerTick = 2;
    public int maxArenaChunks = 4096;
    public String arenaDimension = "hungergames:arena";
    public Position lobby;
    public Map<Integer, Position> spawns = new LinkedHashMap<>();
    public Corner corner1;
    public Corner corner2;
    public Border border = new Border();
    public List<String> containerBlocks = new ArrayList<>(List.of("minecraft:chest", "minecraft:trapped_chest", "ironchest:*"));
    public Bonus bonus = new Bonus();
    public String announcement = "Уважаемые игроки, скоро начнётся королевская битва за награды! "
            + "Освободите инвентарь, броню и аксессуары и напишите /hg invite. Набор открыт на 15 минут, мест: 8.";
    public String battleMessage = "Бой начался! Последний выживший получит награду.";
    public String winnerMessage = "Поздравляем! Победитель: {player}!";

    public record Corner(int x, int y, int z) {}

    public static final class Border {
        public double centerX = 0;
        public double centerZ = 0;
        public double initialSize = 256;
        public double finalSize = 8;
        public int delaySeconds = 60;
        public int shrinkSeconds = 600;
        public double damagePerBlock = 1;
        public double safeZone = 0;
        public int warningBlocks = 10;
    }

    public static final class Bonus {
        public String item = "minecraft:nether_star";
        public int count = 1;
        public Map<Integer, Double> chances = new LinkedHashMap<>(Map.of(4, .2, 5, .4, 6, .6, 7, .8, 8, 1.0));
    }

    public void validate() {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported settings schemaVersion");
        LocalTime.parse(scheduleTime);
        ZoneId.of(scheduleZone);
        ResourceLocation.parse(arenaDimension);
        if (List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end").contains(arenaDimension)) {
            throw new IllegalArgumentException("Arena must use a separate dimension, such as hungergames:arena");
        }
        if (minPlayers < 2 || maxPlayers < minPlayers || maxPlayers > 8 || registrationSeconds < 1
                || countdownSeconds < 1 || fullCountdownSeconds < 1 || maxMatchSeconds < 0
                || chunksPerTick < 1 || chunksPerTick > 64 || maxArenaChunks < 1 || maxArenaChunks > 65536) {
            throw new IllegalArgumentException("Invalid player limits or timer/scan settings");
        }
        if (lobby != null) lobby.validate();
        if (spawns == null || containerBlocks == null || border == null || bonus == null || announcement == null
                || battleMessage == null || winnerMessage == null) {
            throw new IllegalArgumentException("Missing settings section");
        }
        spawns.forEach((slot, position) -> {
            if (slot < 1 || slot > 8 || position == null) throw new IllegalArgumentException("Invalid spawn slot: " + slot);
            position.validate();
        });
        for (String block : containerBlocks) {
            if (block.endsWith(":*")) ResourceLocation.fromNamespaceAndPath(block.substring(0, block.length() - 2), "placeholder");
            else ResourceLocation.parse(block);
        }
        if (!Double.isFinite(border.initialSize) || !Double.isFinite(border.finalSize)
                || !Double.isFinite(border.centerX) || !Double.isFinite(border.centerZ)
                || !Double.isFinite(border.damagePerBlock) || !Double.isFinite(border.safeZone)
                || Math.abs(border.centerX) > 29_000_000 || Math.abs(border.centerZ) > 29_000_000
                || border.initialSize < 1 || border.initialSize > 59_000_000 || border.finalSize < 1
                || border.finalSize > border.initialSize || border.delaySeconds < 0 || border.shrinkSeconds < 1
                || border.damagePerBlock < 0 || border.safeZone < 0 || border.warningBlocks < 0) {
            throw new IllegalArgumentException("Invalid border settings");
        }
        ResourceLocation.parse(bonus.item);
        if (bonus.count < 1 || bonus.count > 64 || bonus.chances == null
                || bonus.chances.values().stream().anyMatch(p -> p == null || !Double.isFinite(p) || p < 0 || p > 1)) {
            throw new IllegalArgumentException("Invalid bonus settings");
        }
    }
}
