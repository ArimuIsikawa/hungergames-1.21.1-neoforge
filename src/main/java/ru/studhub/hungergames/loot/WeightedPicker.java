package ru.studhub.hungergames.loot;

import java.util.List;
import java.util.function.ToLongFunction;
import java.util.random.RandomGenerator;

public final class WeightedPicker {
    private WeightedPicker() {}

    public static <T> T pick(List<T> entries, ToLongFunction<T> weight, RandomGenerator random) {
        long total = 0;
        for (T entry : entries) {
            long value = weight.applyAsLong(entry);
            if (value <= 0) throw new IllegalArgumentException("Weight must be positive");
            total = Math.addExact(total, value);
        }
        if (total == 0) throw new IllegalArgumentException("Pool is empty");
        long roll = random.nextLong(total);
        for (T entry : entries) {
            roll -= weight.applyAsLong(entry);
            if (roll < 0) return entry;
        }
        throw new IllegalStateException("Invalid weighted roll");
    }
}
