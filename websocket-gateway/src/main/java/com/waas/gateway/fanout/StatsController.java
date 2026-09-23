package com.waas.gateway.fanout;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Live counters for the dashboard's status panel: is fan-out actually doing work? */
@RestController
public class StatsController {

    private final SubscriptionRegistry registry;
    private final FanoutService fanout;

    public StatsController(SubscriptionRegistry registry, FanoutService fanout) {
        this.registry = registry;
        this.fanout = fanout;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
                "sessions", registry.sessionCount(),
                "subscriptions", registry.subscriptionCount(),
                "watchedWaitlists", registry.watchedWaitlists().size(),
                "eventsReceived", fanout.eventsReceived.get(),
                "pushes", fanout.pushes.get(),
                "snapshotFetches", fanout.snapshotFetches.get(),
                "ticks", fanout.ticks.get());
    }
}
