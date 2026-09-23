package com.waas.core.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.waas.core.AbstractIntegrationTest;
import com.waas.core.common.error.ApiException;
import com.waas.core.group.GroupService.GroupView;
import com.waas.core.queue.QueueService;
import com.waas.core.reservation.ReservationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GroupFlowTest extends AbstractIntegrationTest {

    @Autowired GroupService groups;
    @Autowired QueueService queue;
    @Autowired ReservationService reservations;

    private UUID waitlist(String policy, int capacity) {
        UUID w = newWaitlist(capacity, 600);
        jdbc.sql("UPDATE waitlist SET group_policy = :p WHERE id = :id").param("p", policy).param("id", w).update();
        return w;
    }

    @Test
    void strictGroupIsOneEntryAndOneSlot() {
        UUID w = waitlist("STRICT", 1);
        UUID a = newUser("A"), b = newUser("B"), c = newUser("C");

        GroupView g = groups.create(w, a, List.of(b, c));

        assertThat(g.members()).hasSize(3);
        assertThat(g.members()).extracting(GroupService.MemberView::entryId).containsOnly(g.members().get(0).entryId());
        var s = queue.snapshot(w, 10);
        assertThat(s.reservedCount()).isEqualTo(1);
        assertThat(s.reserved().get(0).groupSize()).isEqualTo(3);
    }

    @Test
    void strictGroupIsConfirmedOnlyWhenEveryoneConfirms() {
        UUID w = waitlist("STRICT", 1);
        UUID a = newUser("A"), b = newUser("B");
        GroupView g = groups.create(w, a, List.of(b));
        UUID entry = g.members().get(0).entryId();

        // the creator can't confirm for everyone through the normal endpoint
        assertThatThrownBy(() -> reservations.confirm(w, entry, a))
                .isInstanceOf(ApiException.class).hasMessageContaining("group member");

        groups.confirm(w, g.groupId(), a);
        assertThat(stateOf(entry)).isEqualTo("RESERVED");
        groups.confirm(w, g.groupId(), b);
        assertThat(stateOf(entry)).isEqualTo("CONFIRMED");
    }

    @Test
    void partialGroupMembersGetTheirOwnEntries() {
        UUID w = waitlist("PARTIAL", 1);
        UUID a = newUser("A"), b = newUser("B"), c = newUser("C");

        GroupView g = groups.create(w, a, List.of(b, c));

        assertThat(g.members()).extracting(GroupService.MemberView::entryId).doesNotHaveDuplicates();
        var s = queue.snapshot(w, 10);
        assertThat(s.reservedCount()).isEqualTo(1);   // the first member got the slot
        assertThat(s.queueSize()).isEqualTo(2);
    }

    @Test
    void someoneAlreadyInTheQueueCantBeGrouped() {
        UUID w = waitlist("STRICT", 1);
        UUID a = newUser("A"), b = newUser("B");
        queue.join(w, b);

        assertThatThrownBy(() -> groups.create(w, a, List.of(b)))
                .isInstanceOf(ApiException.class).hasMessageContaining("already");
    }
}
