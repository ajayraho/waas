package com.waas.core.referral;

import static org.assertj.core.api.Assertions.assertThat;

import com.waas.core.AbstractIntegrationTest;
import com.waas.core.common.redis.RedisKeys;
import com.waas.core.queue.QueueRebuilder;
import com.waas.core.queue.QueueService;
import com.waas.core.queue.QueueViews.WaitingRow;
import com.waas.core.referral.ReferralRepository.Ledger;
import com.waas.core.reservation.ReservationService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class ReferralFlowTest extends AbstractIntegrationTest {

    @Autowired QueueService queue;
    @Autowired ReservationService reservations;
    @Autowired ReferralService referrals;
    @Autowired QueueRebuilder rebuilder;
    @Autowired StringRedisTemplate redis;

    private record Person(String name, UUID userId, UUID entryId) {}

    /** Capacity-1 waitlist with bump=2: the slot holder joins first, then {@code names} queue up in order. */
    private List<Person> queueUp(UUID w, String... names) {
        List<Person> people = new ArrayList<>();
        for (String n : names) {
            UUID u = newUser(n);
            people.add(new Person(n, u, queue.join(w, u).entryId()));
        }
        return people;
    }

    private List<String> waitingNames(UUID w) {
        return queue.snapshot(w, 200).waiting().stream().map(WaitingRow::name).toList();
    }

    /** A new user joins through {@code referrer}'s link. */
    private void referredJoin(UUID w, Person referrer, String name) {
        queue.join(w, newUser(name), referrer.userId());
    }

    @Test
    void aReferralMovesTheReferrerUpExactlyNSpots() {
        UUID w = newWaitlist(1, 600, 2);
        List<Person> p = queueUp(w, "Holder", "A", "B", "C", "D", "E");  // Holder gets the slot
        Person e = p.get(5);
        assertThat(waitingNames(w)).containsExactly("A", "B", "C", "D", "E");

        referredJoin(w, e, "Friend");

        assertThat(waitingNames(w)).containsExactly("A", "B", "E", "C", "D", "Friend");
        Ledger l = referrals.ledger(w, e.userId());
        assertThat(l).isEqualTo(new Ledger(2, 2, 0, 1, 0));
    }

    @Test
    void creditsBeyondTheHeadAreBanked() {
        UUID w = newWaitlist(1, 600, 2);
        List<Person> p = queueUp(w, "Holder", "A", "B");
        Person b = p.get(2); // rank 1: only one spot to gain

        referredJoin(w, b, "Friend");

        assertThat(waitingNames(w)).startsWith("B", "A");
        assertThat(referrals.ledger(w, b.userId())).isEqualTo(new Ledger(2, 1, 1, 1, 0));
    }

    @Test
    void aReservedReferrerBanksEverything() {
        UUID w = newWaitlist(1, 600, 2);
        Person holder = queueUp(w, "Holder").get(0); // RESERVED

        referredJoin(w, holder, "Friend");

        assertThat(referrals.ledger(w, holder.userId())).isEqualTo(new Ledger(2, 0, 2, 1, 0));
    }

    @Test
    void bankedCreditsAreSpentWhenTheReferrerRejoins() {
        UUID w = newWaitlist(1, 600, 2);
        List<Person> p = queueUp(w, "Holder", "A", "B", "C");
        Person holder = p.get(0);
        referredJoin(w, holder, "Friend");                      // holder is RESERVED → 2 banked
        reservations.decline(w, holder.entryId(), holder.userId()); // A promoted; holder CANCELLED
        assertThat(waitingNames(w)).containsExactly("B", "C", "Friend");

        queue.join(w, holder.userId());                         // back of the queue, then spends 2

        assertThat(waitingNames(w)).containsExactly("B", "Holder", "C", "Friend");
        assertThat(referrals.ledger(w, holder.userId())).isEqualTo(new Ledger(2, 2, 0, 1, 0));
    }

    @Test
    void theSameReferralNeverPaysTwice() {
        UUID w = newWaitlist(1, 600, 1);
        List<Person> p = queueUp(w, "Holder", "A", "B", "C");
        Person c = p.get(3);
        UUID friend = newUser("Friend");

        UUID first = queue.join(w, friend, c.userId()).entryId();
        queue.join(w, friend, c.userId());           // idempotent repeat: no new entry, no event
        queue.cancel(w, first);
        queue.join(w, friend, c.userId());           // a real re-join through the same link

        Ledger l = referrals.ledger(w, c.userId());
        assertThat(l.earned()).isEqualTo(1);
        assertThat(l.credited()).isEqualTo(1);
    }

    @Test
    void selfReferralAndForgedReferrersEarnNothing() {
        UUID w = newWaitlist(1, 600, 2);
        queueUp(w, "Holder", "A");
        UUID me = newUser("Me");

        queue.join(w, me, me);                        // ?ref= myself
        queue.join(w, newUser("X"), UUID.randomUUID()); // ?ref= a user that doesn't exist

        assertThat(referrals.ledger(w, me)).isEqualTo(new Ledger(0, 0, 0, 0, 0));
        assertThat(waitingNames(w)).containsExactly("A", "Me", "X"); // both joins still succeeded
    }

    @Test
    void theVelocityLimitRejectsButRecordsExcessReferrals() {
        UUID w = newWaitlist(1, 600, 1);
        Person holder = queueUp(w, "Holder").get(0);
        for (int i = 0; i < 7; i++) {
            referredJoin(w, holder, "Bot" + i);
        }

        Ledger l = referrals.ledger(w, holder.userId());
        assertThat(l.credited()).isEqualTo(5);   // waas.referral.velocity-limit default
        assertThat(l.rejected()).isEqualTo(2);
        assertThat(l.earned()).isEqualTo(5);
        assertThat(referrals.recent(w, 20)).filteredOn(a -> a.status().equals("REJECTED"))
                .allSatisfy(a -> assertThat(a.rejectionReason()).isEqualTo("VELOCITY_LIMIT"));
    }

    @Test
    void bumpedOrderSurvivesARedisRebuild() {
        UUID w = newWaitlist(1, 600, 2);
        List<Person> p = queueUp(w, "Holder", "A", "B", "C", "D", "E");
        referredJoin(w, p.get(5), "F1");
        referredJoin(w, p.get(4), "F2");
        List<String> before = waitingNames(w);

        redis.delete(List.of(RedisKeys.queue(w), RedisKeys.reserved(w)));
        rebuilder.rebuild(w);

        assertThat(waitingNames(w)).containsExactlyElementsOf(before);
    }
}
