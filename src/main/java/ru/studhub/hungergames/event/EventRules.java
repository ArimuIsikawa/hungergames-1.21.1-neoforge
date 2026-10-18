package ru.studhub.hungergames.event;

import java.time.LocalTime;
import java.time.ZonedDateTime;

public final class EventRules {
    private EventRules() {}

    public static long countdown(long currentDeadline, long now, int count, int minimum, int maximum,
                                 int normalSeconds, int fullSeconds) {
        if (count < minimum) return -1;
        long deadline = currentDeadline < 0 ? now + normalSeconds : currentDeadline;
        return count >= maximum ? Math.min(deadline, now + fullSeconds) : deadline;
    }

    public static boolean scheduleDue(ZonedDateTime now, LocalTime time, int windowSeconds, String lastDate) {
        ZonedDateTime opening = now.toLocalDate().atTime(time).atZone(now.getZone());
        return !now.toLocalDate().toString().equals(lastDate) && !now.isBefore(opening)
                && now.isBefore(opening.plusSeconds(windowSeconds));
    }

    public static double bonusChance(int startingPlayers) {
        return startingPlayers < 16 ? 0.0 : Math.clamp(.2 + (startingPlayers - 16) * .05, 0.0, 1.0);
    }
}
