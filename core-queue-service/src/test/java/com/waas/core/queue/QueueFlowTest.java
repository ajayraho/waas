package com.waas.core.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.waas.core.AbstractIntegrationTest;
import com.waas.core.common.error.ApiException;
import com.waas.core.common.redis.RedisKeys;
import com.waas.core.queue.QueueViews.JoinResponse;
import com.waas.core.queue.QueueViews.QueueSnapshot;
import com.waas.core.queue.QueueViews.WaitingRow;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class QueueFlowTest extends AbstractIntegrationTest {

    @Autowired QueueService queue;
    @Autowired QueueRebuilder rebuilder;
    @Autowired StringRedisTemplate redis;

    /** A capacity-1 waitlist whose single slot is already taken, so new joins stay WAITING. */
    private UUID waitlistWithSlotTaken() {
        UUID w = newWaitlist(1, 600);
        assertThat(queue.join(w, newUser("SlotHolder")).position().state()).isEqualTo(EntryState.RESERVED);
        return w;
    }

    @Test
    void startupRebuildLoadsTheSeedIntoRedis() {
        QueueSnapshot s = queue.snapshot(AIRMAX, 50);

        assertThat(s.waiting()).extracting(WaitingRow::name).startsWith("Alice", "Bob", "Carol", "Dave", "Eve");
        assertThat(s.waiting().get(0).score()).isEqualTo(1.0);
        // Alice's row carries her referral story: 4 places gained, 3 friends, and when she joined
        WaitingRow alice = s.waiting().get(0);
        assertThat(alice.boost()).isEqualTo(4);
        assertThat(alice.referrals()).isEqualTo(3);
        assertThat(alice.joinedAt()).isBefore(s.at());
        assertThat(s.reserved()).singleElement().satisfies(r -> assertThat(r.name()).isEqualTo("Frank"));
    }

    @Test
    void joinAppendsAtTheBackAndRepeatingItIsIdempotent() {
        UUID w = waitlistWithSlotTaken();
        UUID user = newUser("Zara");

        JoinResponse first = queue.join(w, user);
        JoinResponse again = queue.join(w, user);

        assertThat(first.created()).isTrue();
        assertThat(first.position().state()).isEqualTo(EntryState.WAITING);
        assertThat(first.position().position()).isEqualTo(first.position().queueSize());
        assertThat(again.created()).isFalse();
        assertThat(again.entryId()).isEqualTo(first.entryId());
        assertThat(again.position().position()).isEqualTo(first.position().position());
    }

    @Test
    void concurrentJoinsEndInJoinSequenceOrderWithContiguousPositions() throws Exception {
        UUID w = waitlistWithSlotTaken();
        int n = 40;
        List<UUID> people = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            people.add(newUser("Burst" + i));
        }

        List<Callable<JoinResponse>> joins = people.stream()
                .<Callable<JoinResponse>>map(u -> () -> queue.join(w, u))
                .toList();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (Future<JoinResponse> f : pool.invokeAll(joins)) {
                assertThat(f.get().created()).isTrue();
            }
        }

        // Positions in each response are "rank at that instant" and may legitimately repeat
        // under concurrency. The invariant is the final queue: everyone present, positions
        // 1..n, ordered by join_sequence (the ZSET score).
        QueueSnapshot s = queue.snapshot(w, 200);
        assertThat(s.waiting()).extracting(WaitingRow::position)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, n).boxed().toList());
        List<Double> scores = s.waiting().stream().map(WaitingRow::score).toList();
        assertThat(scores).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void cancelRemovesTheEntryAndIsIdempotent() {
        UUID w = waitlistWithSlotTaken();
        UUID user = newUser("Quinn");
        JoinResponse joined = queue.join(w, user);

        queue.cancel(w, joined.entryId());
        queue.cancel(w, joined.entryId()); // second call is a no-op

        assertThat(queue.position(w, joined.entryId()).state()).isEqualTo(EntryState.CANCELLED);
        assertThat(queue.snapshot(w, 200).waiting()).noneMatch(r -> r.entryId().equals(joined.entryId()));

        // A cancelled user may join again: the partial unique index ignores terminal states.
        assertThat(queue.join(w, user).created()).isTrue();
    }

    @Test
    void cancellingAnUnknownEntryIs404() {
        UUID w = newWaitlist(1, 600);
        assertThatThrownBy(() -> queue.cancel(w, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No entry");
    }

    @Test
    void rebuildRestoresExactOrderAfterRedisLosesTheWaitlist() {
        List<UUID> before = queue.snapshot(AIRMAX, 200).waiting().stream().map(WaitingRow::entryId).toList();

        redis.delete(List.of(RedisKeys.queue(AIRMAX), RedisKeys.reserved(AIRMAX)));
        assertThat(queue.snapshot(AIRMAX, 200).waiting()).isEmpty();

        rebuilder.rebuild(AIRMAX);

        List<UUID> after = queue.snapshot(AIRMAX, 200).waiting().stream().map(WaitingRow::entryId).toList();
        assertThat(after).containsExactlyElementsOf(before);
        assertThat(queue.snapshot(AIRMAX, 200).reserved()).hasSize(1);
    }

    @Test
    void retryingAJoinRepairsAMissingRedisMember() {
        UUID w = waitlistWithSlotTaken();
        UUID user = newUser("Rita");
        JoinResponse joined = queue.join(w, user);

        // Simulate the Redis half of the join having failed.
        redis.opsForZSet().remove(RedisKeys.queue(w), joined.entryId().toString());
        assertThat(queue.position(w, joined.entryId()).position()).isNull();

        JoinResponse retried = queue.join(w, user);

        assertThat(retried.created()).isFalse();
        assertThat(retried.position().position()).isNotNull();
        assertThat(queue.snapshot(w, 200).waiting()).anyMatch(r -> r.entryId().equals(joined.entryId()));
    }
}
