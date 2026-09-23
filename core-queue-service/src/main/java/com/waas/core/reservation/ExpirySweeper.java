package com.waas.core.reservation;

import com.waas.core.tenant.Waitlist;
import com.waas.core.tenant.WaitlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The expiry mechanism, built (ARCHITECTURE.md §21.2): a 1 s fixed-delay tick.
 *
 * <p>Cost per tick is one {@code expire_due.lua} + one {@code promote.lua} per waitlist, and each is
 * O(log N + work done), because the reserved set is indexed by expiry. That's why this isn't
 * the "poll every RESERVED row" design §12.4 warned about. The documented upgrade is Redis
 * keyspace notifications (push instead of tick), for when the waitlist count makes even an
 * idle tick per waitlist too many.
 *
 * <p>Precision: an expiry is noticed within ~1 s. confirm.lua checks the clock itself, so a
 * late sweep never lets someone confirm after their window.
 */
@Component
class ExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ExpirySweeper.class);

    private final WaitlistService waitlists;
    private final ReservationService reservations;

    ExpirySweeper(WaitlistService waitlists, ReservationService reservations) {
        this.waitlists = waitlists;
        this.reservations = reservations;
    }

    @Scheduled(fixedDelayString = "${waas.sweeper.interval-ms:1000}", initialDelayString = "${waas.sweeper.initial-delay-ms:2000}")
    void tick() {
        for (Waitlist w : waitlists.listActive()) {
            try {
                int expired = reservations.sweep(w.id());
                if (expired > 0) {
                    log.info("Expired {} reservation(s) on waitlist {}", expired, w.id());
                }
            } catch (RuntimeException e) {
                // One broken waitlist must not stop the others from being swept.
                log.error("Sweep failed for waitlist {}", w.id(), e);
            }
        }
    }
}
