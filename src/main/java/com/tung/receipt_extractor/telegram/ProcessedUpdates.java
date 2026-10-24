package com.tung.receipt_extractor.telegram;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers recently seen Telegram update ids so that webhook redeliveries are not processed twice.
 * In-memory, so it only dedupes within a single running instance.
 */
@Component
public class ProcessedUpdates {

    static final Duration TTL = Duration.ofHours(1);

    private final Clock clock;
    private final ConcurrentHashMap<Long, Instant> seen = new ConcurrentHashMap<>();

    public ProcessedUpdates(Clock clock) {
        this.clock = clock;
    }

    /** Returns true the first time an update id is seen (within the TTL), false for any repeat. */
    public boolean markIfNew(long updateId) {
        Instant now = clock.instant();
        seen.values().removeIf(seenAt -> seenAt.plus(TTL).isBefore(now));
        return seen.putIfAbsent(updateId, now) == null;
    }
}
