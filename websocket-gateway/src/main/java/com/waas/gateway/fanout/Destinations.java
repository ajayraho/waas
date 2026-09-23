package com.waas.gateway.fanout;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The STOMP destinations clients may subscribe to.
 *
 * <ul>
 *   <li>{@code /topic/waitlists/{w}/queue} — the dashboard's whole-queue view (a Core snapshot)</li>
 *   <li>{@code /topic/waitlists/{w}/entries/{e}} — one person's position / state / countdown</li>
 * </ul>
 * The same paths under {@code /app/...} are one-shot "give me the current state" subscriptions,
 * answered by {@link InitialStateController}.
 */
public final class Destinations {

    private Destinations() {}

    private static final Pattern QUEUE = Pattern.compile("^/topic/waitlists/([0-9a-fA-F-]{36})/queue$");
    private static final Pattern ENTRY = Pattern.compile("^/topic/waitlists/([0-9a-fA-F-]{36})/entries/([0-9a-fA-F-]{36})$");

    public static String queue(UUID waitlistId) {
        return "/topic/waitlists/" + waitlistId + "/queue";
    }

    public static String entry(UUID waitlistId, UUID entryId) {
        return "/topic/waitlists/" + waitlistId + "/entries/" + entryId;
    }

    /** A parsed subscription target: an entry (entryId non-null) or a whole queue. */
    public record Target(UUID waitlistId, UUID entryId) {
        public boolean isQueue() {
            return entryId == null;
        }
    }

    public static Optional<Target> parse(String destination) {
        if (destination == null) {
            return Optional.empty();
        }
        Matcher m = ENTRY.matcher(destination);
        if (m.matches()) {
            return Optional.of(new Target(UUID.fromString(m.group(1)), UUID.fromString(m.group(2))));
        }
        m = QUEUE.matcher(destination);
        if (m.matches()) {
            return Optional.of(new Target(UUID.fromString(m.group(1)), null));
        }
        return Optional.empty();
    }
}
