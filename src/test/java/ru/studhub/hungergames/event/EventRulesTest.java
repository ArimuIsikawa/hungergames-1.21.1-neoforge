package ru.studhub.hungergames.event;

import static org.junit.jupiter.api.Assertions.*;
import java.time.ZoneId;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class EventRulesTest {
    private long update(long deadline, long now, int players) {
        return EventRules.countdown(deadline, now, players, 16, 32, 180, 15);
    }

    @Test void countdownResetsOnlyBelowMinimum() {
        assertEquals(-1, update(-1, 100, 15));
        assertEquals(280, update(-1, 100, 16));
        assertEquals(280, update(280, 120, 31));
        assertEquals(-1, update(280, 130, 15));
        assertEquals(320, update(-1, 140, 16));
    }

    @Test void thirtySecondPlayerShortensButNeverExtendsTheDeadline() {
        assertEquals(135, update(280, 120, 32));
        assertEquals(135, update(135, 122, 31));
        assertEquals(130, update(130, 128, 32));
    }

    @Test void scheduleHonoursMoscowWindowAndPersistentDate() {
        ZoneId moscow = ZoneId.of("Europe/Moscow");
        ZonedDateTime opening = ZonedDateTime.of(2026, 10, 16, 20, 0, 0, 0, moscow);
        assertFalse(EventRules.scheduleDue(opening.minusSeconds(1), LocalTime.of(20, 0), 900, ""));
        assertTrue(EventRules.scheduleDue(opening, LocalTime.of(20, 0), 900, ""));
        assertTrue(EventRules.scheduleDue(opening.plusMinutes(14), LocalTime.of(20, 0), 900, ""));
        assertFalse(EventRules.scheduleDue(opening.plusMinutes(15), LocalTime.of(20, 0), 900, ""));
        assertFalse(EventRules.scheduleDue(opening, LocalTime.of(20, 0), 900, "2026-10-16"));
    }

    @Test void forcedSmallMatchesDoNotInventABonusChance() {
        assertEquals(0, EventRules.bonusChance(0));
        assertEquals(0, EventRules.bonusChance(15));
        for (int players = 16; players <= 32; players++) assertEquals(.2 + (players - 16) * .05,
                EventRules.bonusChance(players), .000001);
        assertEquals(1, EventRules.bonusChance(32));
        assertEquals(1, EventRules.bonusChance(40));
    }
}
