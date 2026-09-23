package com.waas.gateway.fanout;

import java.util.UUID;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

/**
 * One-shot "current state" subscriptions (§21.8 reconnect resync).
 *
 * <p>A client subscribes to {@code /app/waitlists/{w}/…} and gets exactly one message, sent straight
 * back to that subscriber (not through the broker), then keeps its {@code /topic/…} subscription
 * for updates. This is Spring's {@code @SubscribeMapping} pattern, and it avoids a race: a push
 * sent to a {@code /topic} the instant a SUBSCRIBE arrives can beat the broker registering it.
 *
 * <p>A reconnecting client just does this again: one round trip, fully in sync, no event replay.
 */
@Controller
public class InitialStateController {

    private static final String UNAVAILABLE = "{\"error\":\"UNAVAILABLE\"}";

    private final FanoutService fanout;
    private final CoreClient core;

    public InitialStateController(FanoutService fanout, CoreClient core) {
        this.fanout = fanout;
        this.core = core;
    }

    @SubscribeMapping("/waitlists/{waitlistId}/queue")
    public String queue(@DestinationVariable UUID waitlistId) {
        return core.snapshot(waitlistId).orElse(UNAVAILABLE);
    }

    @SubscribeMapping("/waitlists/{waitlistId}/entries/{entryId}")
    public String entry(@DestinationVariable UUID waitlistId, @DestinationVariable UUID entryId) {
        String current = fanout.currentEntryJson(waitlistId, entryId);
        return current != null ? current : UNAVAILABLE;
    }
}
