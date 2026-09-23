package com.waas.core.reservation;

import com.waas.core.auth.CurrentUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Actions for the holder of a reservation. POST, not PATCH/PUT: these are commands with
 * side effects (freeing a slot, promoting the next person), not field updates.
 */
@RestController
@RequestMapping("/api/waitlists/{waitlistId}/entries/{entryId}")
public class ReservationController {

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@PathVariable UUID waitlistId, @PathVariable UUID entryId,
                        @CurrentUser UUID userId) {
        reservations.confirm(waitlistId, entryId, userId);
    }

    @PostMapping("/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@PathVariable UUID waitlistId, @PathVariable UUID entryId,
                        @CurrentUser UUID userId) {
        reservations.decline(waitlistId, entryId, userId);
    }
}
