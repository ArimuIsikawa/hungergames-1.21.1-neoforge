package ru.studhub.hungergames.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ru.studhub.hungergames.arena.ArenaEnvironment;
import ru.studhub.hungergames.arena.ArenaEnvironment.Weather;

@GameTestHolder("hungergames")
@PrefixGameTestTemplate(false)
public final class EnvironmentGameTests {
    private record Scenario(Weather selected, Weather expected, long time, int... choices) {}

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void fixedWeatherAndMoonPhases(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("hungergames:weather_test")));
        helper.assertTrue(level != null, "Weather test dimension is missing");
        var original = level.getLevelData();
        var overworld = server.overworld();
        long overworldTime = overworld.getDayTime();
        boolean daylight = overworld.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
        boolean weatherCycle = overworld.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE);
        BlockPos sky = new BlockPos(0, 100, 0);
        level.getChunkAt(sky);
        var environment = new ArenaEnvironment(server);
        environment.configure(level);
        List<Scenario> scenarios = new ArrayList<>();
        long[] times = {0, 6000, 12000, 18000, 114000};
        for (int i = 0; i < times.length; i++) scenarios.add(new Scenario(Weather.CLEAR, Weather.CLEAR, times[i], i));
        scenarios.add(new Scenario(Weather.RAIN, Weather.RAIN, 6000));
        scenarios.add(new Scenario(Weather.THUNDER, Weather.THUNDER, 6000));
        scenarios.add(new Scenario(null, Weather.CLEAR, 18000, 0, 3));
        scenarios.add(new Scenario(null, Weather.RAIN, 6000, 1));
        scenarios.add(new Scenario(null, Weather.THUNDER, 6000, 2));
        int[] stage = {0}, elapsed = {0};
        helper.onEachTick(() -> {
            try {
                if (stage[0] >= scenarios.size()) {
                    environment.resetWeather();
                    helper.assertTrue(level.getDayTime() == 6000 && !level.isRaining() && !level.isThundering(), "Reset lost idle weather");
                    environment.close();
                    helper.assertTrue(level.getLevelData() == original, "Closing environment did not restore original data");
                    helper.assertTrue(!daylight || overworld.getDayTime() > overworldTime, "Overworld time stopped with the arena");
                    helper.succeed();
                    return;
                }
                Scenario scenario = scenarios.get(stage[0]);
                if (elapsed[0] % 25 == 0) {
                    long before = overworld.getDayTime();
                    boolean raining = overworld.isRaining(), thundering = overworld.isThundering();
                    environment.setEventWeather(scenario.selected, choices(scenario.choices));
                    helper.assertTrue(overworld.getDayTime() == before && overworld.isRaining() == raining
                            && overworld.isThundering() == thundering, "Weather selection modified the overworld");
                    // Vanilla setters and sleep/weather resets must not alter the fixed arena conditions.
                    level.setDayTime(12345);
                    level.setWeatherParameters(100, 100, !level.isRaining(), !level.isThundering());
                    ServerLevelData data = (ServerLevelData) level.getLevelData();
                    data.setDayTimeFraction(.5F);
                    data.setDayTimePerTick(2);
                }
                helper.assertTrue(level.getDayTime() == scenario.time, "Arena time advanced or selected the wrong sky");
                helper.assertTrue(level.isRaining() == (scenario.expected != Weather.CLEAR)
                        && level.isThundering() == (scenario.expected == Weather.THUNDER), "Weather changed during the event");
                helper.assertTrue(level.getRainLevel(1) == (scenario.expected == Weather.CLEAR ? 0 : 1)
                        && level.getThunderLevel(1) == (scenario.expected == Weather.THUNDER ? 1 : 0), "Weather intensity is incorrect");
                helper.assertTrue(!level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)
                        && !level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE), "Natural cycles are enabled");
                helper.assertTrue(overworld.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT) == daylight
                        && overworld.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE) == weatherCycle, "Arena changed global rules");
                helper.assertTrue(level.isRainingAt(sky) == (scenario.expected != Weather.CLEAR), "Rain does not reach open sky in a rainy biome");
                if (scenario.time == 18000) helper.assertTrue(level.getMoonPhase() == 0, "Full moon has wrong phase");
                if (scenario.time == 114000) helper.assertTrue(level.getMoonPhase() == 4, "New moon has wrong phase");
                if (++elapsed[0] % 25 == 0) {
                    environment.tick();
                    stage[0]++;
                }
            } catch (RuntimeException | Error error) {
                environment.close();
                throw error;
            }
        });
    }

    private static Random choices(int... values) {
        return new Random(0) {
            private int index;
            @Override public int nextInt(int bound) {
                int value = values[index++];
                if (value < 0 || value >= bound) throw new IllegalArgumentException("Invalid weather test choice");
                return value;
            }
        };
    }
}
