package com.waas.gateway.fanout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Event → push, the §21.7 model.
 *
 * <ol>
 *   <li><b>An event arrives</b> (Redis Pub/Sub, every gateway gets every event): mark its waitlist
 *       <em>dirty</em>. If it's about an entry someone here is watching and it changes that
 *       person's state (RESERVED, BUMPED, …), push to them immediately.</li>
 *   <li><b>Every tick</b> (500 ms): for each dirty waitlist, recompute positions of the entries
 *       watched <em>here</em> (one pipelined Redis read) and push only the ones that changed.
 *       Dashboard viewers get one Core snapshot per waitlist per tick, however many are watching.</li>
 *   <li><b>Every 5 s</b>: mark everything watched as dirty. Pub/Sub is fire-and-forget; a lost
 *       event costs at most 5 s of staleness, never a stuck screen.</li>
 * </ol>
 *
 * <p>Nobody is ever sent a message just because someone <em>else</em> moved. People behind a
 * bump see their new position on the next tick, computed by ZRANK. A referral storm of
 * 1,000 bumps in one tick costs the same as one bump.
 */
@Service
public class FanoutService {

    private static final Logger log = LoggerFactory.getLogger(FanoutService.class);
    private static final Set<String> PERSONAL = Set.of("RESERVED", "CONFIRMED", "EXPIRED", "CANCELLED", "BUMPED");

    private final SubscriptionRegistry registry;
    private final PositionReader positions;
    private final CoreClient core;
    private final SimpMessagingTemplate messaging;
    private final ObjectMapper json;

    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> lastSent = new ConcurrentHashMap<>();

    final AtomicLong eventsReceived = new AtomicLong();
    final AtomicLong pushes = new AtomicLong();
    final AtomicLong snapshotFetches = new AtomicLong();
    final AtomicLong ticks = new AtomicLong();

    public FanoutService(SubscriptionRegistry registry, PositionReader positions, CoreClient core,
                         SimpMessagingTemplate messaging, ObjectMapper json) {
        this.registry = registry;
        this.positions = positions;
        this.core = core;
        this.messaging = messaging;
        this.json = json;
    }

    /** Called by the Redis listener container for every message on {@code waitlist:{*}:events}. */
    public void onRedisMessage(String body) {
        QueueEvent event;
        try {
            event = json.readValue(body, QueueEvent.class);
        } catch (JsonProcessingException e) {
            log.warn("Unparseable queue event: {}", body);
            return;
        }
        eventsReceived.incrementAndGet();
        if (event.waitlistId() == null) {
            return;
        }
        dirty.add(event.waitlistId());
        if (event.entryId() != null && PERSONAL.contains(event.type())
                && registry.isWatched(event.waitlistId(), event.entryId())) {
            pushEntries(event.waitlistId(), List.of(event.entryId()));
        }
    }

    @Scheduled(fixedDelayString = "${gateway.fanout.tick-ms:500}")
    void tick() {
        ticks.incrementAndGet();
        if (dirty.isEmpty()) {
            return;
        }
        Set<UUID> batch = new HashSet<>(dirty);
        dirty.removeAll(batch);
        Set<UUID> dashboards = registry.watchedQueues();
        for (UUID w : batch) {
            try {
                if (dashboards.contains(w)) {
                    snapshotFetches.incrementAndGet();
                    core.snapshot(w).ifPresent(s -> send(Destinations.queue(w), s));
                }
                Set<UUID> watched = registry.watchedEntries(w);
                if (!watched.isEmpty()) {
                    pushEntries(w, new ArrayList<>(watched));
                }
            } catch (RuntimeException e) {
                log.warn("Fan-out refresh failed for waitlist {}", w, e);
                dirty.add(w); // try again next tick
            }
        }
    }

    @Scheduled(fixedDelayString = "${gateway.fanout.full-refresh-ms:5000}")
    void fullRefresh() {
        dirty.addAll(registry.watchedWaitlists());
        // Forget dedupe state for entries nobody here watches any more.
        Set<UUID> watched = new HashSet<>();
        for (UUID w : registry.watchedWaitlists()) {
            watched.addAll(registry.watchedEntries(w));
        }
        lastSent.keySet().retainAll(watched);
    }

    /** Current state of one entry as JSON (used for the one-shot initial-state subscription too). */
    public String currentEntryJson(UUID waitlistId, UUID entryId) {
        EntryView v = positions.read(waitlistId, entryId).get(entryId);
        return toJson(waitlistId, v);
    }

    private void pushEntries(UUID waitlistId, List<UUID> entryIds) {
        for (Map.Entry<UUID, EntryView> e : positions.read(waitlistId, entryIds).entrySet()) {
            String payload = toJson(waitlistId, e.getValue());
            if (payload != null && !payload.equals(lastSent.put(e.getKey(), payload))) {
                send(Destinations.entry(waitlistId, e.getKey()), payload);
            }
        }
    }

    /** ABSENT in Redis = terminal (or never existed): only Postgres knows which, so ask Core. */
    private String toJson(UUID waitlistId, EntryView v) {
        if ("ABSENT".equals(v.state())) {
            return core.position(waitlistId, v.entryId()).orElse(null);
        }
        try {
            return json.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void send(String destination, String payload) {
        messaging.convertAndSend(destination, payload);
        pushes.incrementAndGet();
    }
}
