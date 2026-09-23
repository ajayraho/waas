package com.waas.core.queue;

import com.waas.core.queue.QueueViews.EntryPosition;
import com.waas.core.queue.QueueViews.JoinResponse;
import com.waas.core.queue.QueueViews.QueueSnapshot;
import com.waas.core.auth.CurrentUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Queue endpoints.
 *
 * <p>The caller is the user in the bearer token ({@code @CurrentUser}).
 */
@RestController
@RequestMapping("/api/waitlists/{waitlistId}")
public class QueueController {

    private final QueueService queue;

    public QueueController(QueueService queue) {
        this.queue = queue;
    }

    /**
     * 201 for a new entry, 200 for an idempotent repeat. The position comes back synchronously:
     * ZRANK is O(log N), so there's no reason to defer it. Joining only becomes 202 + async
     * (as §15 sketched) once a Kafka admission buffer sits in front of Redis.
     *
     * <p>{@code ?ref=<userId>} is the referral link: the referrer earns a bump (Brick 4).
     */
    @PostMapping("/entries")
    public ResponseEntity<JoinResponse> join(@PathVariable UUID waitlistId,
                                             @CurrentUser UUID userId,
                                             @RequestParam(name = "ref", required = false) UUID referrerId) {
        JoinResponse response = queue.join(waitlistId, userId, referrerId);
        return ResponseEntity.status(response.created() ? HttpStatus.CREATED : HttpStatus.OK).body(response);
    }

    @GetMapping("/entries/{entryId}")
    public EntryPosition position(@PathVariable UUID waitlistId, @PathVariable UUID entryId) {
        return queue.position(waitlistId, entryId);
    }

    @DeleteMapping("/entries/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID waitlistId, @PathVariable UUID entryId, @CurrentUser UUID userId) {
        queue.cancel(waitlistId, entryId, userId);
    }

    @GetMapping("/queue")
    public QueueSnapshot snapshot(@PathVariable UUID waitlistId, @RequestParam(defaultValue = "50") int limit) {
        return queue.snapshot(waitlistId, limit);
    }
}
