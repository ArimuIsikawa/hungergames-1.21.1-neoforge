package ru.studhub.hungergames.loot;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class WeightedPickerTest {
    @Test void boundariesRespectWeights() {
        List<Integer> weights = List.of(1, 3, 6);
        for (int roll = 0; roll < 10; roll++) {
            final int value = roll;
            Random random = new Random() { @Override public long nextLong(long bound) { assertEquals(10, bound); return value; } };
            assertEquals(roll == 0 ? 1 : roll < 4 ? 3 : 6, WeightedPicker.pick(weights, Integer::longValue, random));
        }
    }

    @Test void rejectsEmptyInvalidAndOverflowingPools() {
        assertThrows(IllegalArgumentException.class, () -> WeightedPicker.pick(List.<Long>of(), Long::longValue, new Random()));
        assertThrows(IllegalArgumentException.class, () -> WeightedPicker.pick(List.of(0L), Long::longValue, new Random()));
        assertThrows(IllegalArgumentException.class, () -> WeightedPicker.pick(List.of(-1L), Long::longValue, new Random()));
        assertThrows(ArithmeticException.class, () -> WeightedPicker.pick(List.of(Long.MAX_VALUE, 1L), Long::longValue, new Random()));
    }
}
