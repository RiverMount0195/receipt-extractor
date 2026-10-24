package com.tung.receipt_extractor.telegram;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcessedUpdatesTest {

    private static final Instant START = Instant.parse("2026-10-24T00:00:00Z");

    private final Clock clock = mock(Clock.class);
    private final ProcessedUpdates processedUpdates = new ProcessedUpdates(clock);

    @Test
    void firstSightingIsNewAndRepeatIsNot() {
        when(clock.instant()).thenReturn(START);

        assertTrue(processedUpdates.markIfNew(1L));
        assertFalse(processedUpdates.markIfNew(1L));
    }

    @Test
    void differentUpdateIdsAreIndependent() {
        when(clock.instant()).thenReturn(START);

        assertTrue(processedUpdates.markIfNew(1L));
        assertTrue(processedUpdates.markIfNew(2L));
    }

    @Test
    void repeatWithinTtlIsStillDuplicate() {
        when(clock.instant()).thenReturn(START, START.plus(ProcessedUpdates.TTL));

        assertTrue(processedUpdates.markIfNew(1L));
        assertFalse(processedUpdates.markIfNew(1L));
    }

    @Test
    void updateIdIsAcceptedAgainAfterTtlExpires() {
        when(clock.instant()).thenReturn(START, START.plus(ProcessedUpdates.TTL).plusSeconds(1));

        assertTrue(processedUpdates.markIfNew(1L));
        assertTrue(processedUpdates.markIfNew(1L));
    }
}
