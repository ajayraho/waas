package com.waas.core.group;

import com.waas.core.common.error.ApiException;
import com.waas.core.queue.EntryRepository;
import com.waas.core.queue.EntryState;
import com.waas.core.queue.QueueEntry;
import com.waas.core.queue.QueueService;
import com.waas.core.reservation.ReservationService;
import com.waas.core.tenant.Waitlist;
import com.waas.core.tenant.WaitlistService;
import com.waas.core.user.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * STRICT: the whole group is one queue entry (owned by the creator) and takes one slot.
 *         It only becomes CONFIRMED once every member has confirmed.
 * PARTIAL: every member gets their own entry and moves on their own; the group is just a label.
 */
@Service
public class GroupService {

    static final int MAX_GROUP_SIZE = 8;

    private final WaitlistService waitlists;
    private final QueueService queue;
    private final ReservationService reservations;
    private final EntryRepository entries;
    private final UserRepository users;
    private final GroupRepository groups;

    public GroupService(WaitlistService waitlists, QueueService queue, ReservationService reservations,
                        EntryRepository entries, UserRepository users, GroupRepository groups) {
        this.waitlists = waitlists;
        this.queue = queue;
        this.reservations = reservations;
        this.entries = entries;
        this.users = users;
        this.groups = groups;
    }

    public record MemberView(UUID userId, String name, boolean confirmed, UUID entryId, EntryState state) {}

    public record GroupView(UUID groupId, UUID waitlistId, String policy, List<MemberView> members) {}

    public GroupView create(UUID waitlistId, UUID creatorId, List<UUID> otherMembers) {
        Waitlist w = waitlists.require(waitlistId);

        LinkedHashSet<UUID> members = new LinkedHashSet<>();
        members.add(creatorId);
        members.addAll(otherMembers);
        if (members.size() < 2 || members.size() > MAX_GROUP_SIZE) {
            throw ApiException.badRequest("BAD_GROUP_SIZE", "A group has 2 to " + MAX_GROUP_SIZE + " people");
        }
        for (UUID m : members) {
            users.findById(m).orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "No user " + m));
        }
        if (!groups.alreadyQueued(waitlistId, members).isEmpty()) {
            throw ApiException.conflict("ALREADY_QUEUED", "Someone in the group is already in this waitlist");
        }

        List<UUID> memberList = new ArrayList<>(members);
        UUID groupId = groups.create(waitlistId, creatorId, memberList);
        if ("STRICT".equals(w.groupPolicy())) {
            queue.joinAsGroup(waitlistId, creatorId, groupId);          // one entry for everyone
        } else {
            for (UUID m : memberList) {
                queue.joinAsGroup(waitlistId, m, groupId);               // one entry each
            }
        }
        return view(waitlistId, groupId);
    }

    /** STRICT only: one member confirms; the entry is confirmed when the last one does. */
    public GroupView confirm(UUID waitlistId, UUID groupId, UUID userId) {
        Waitlist w = waitlists.require(waitlistId);
        if (!"STRICT".equals(w.groupPolicy())) {
            throw ApiException.conflict("NOT_STRICT", "In a PARTIAL waitlist members confirm their own entries");
        }
        requireGroup(waitlistId, groupId);
        if (!groups.isMember(groupId, userId)) {
            throw ApiException.notFound("GROUP_NOT_FOUND", "No group " + groupId);
        }
        QueueEntry entry = groupEntry(waitlistId, groupId);
        if (entry.state() != EntryState.RESERVED) {
            throw ApiException.conflict("NOT_RESERVED", "The group isn't at the front yet");
        }
        groups.confirmMember(groupId, userId);
        if (groups.members(groupId).stream().allMatch(GroupRepository.Member::confirmed)) {
            reservations.confirmEntry(entry);
        }
        return view(waitlistId, groupId);
    }

    public GroupView view(UUID waitlistId, UUID groupId) {
        Waitlist w = waitlists.require(waitlistId);
        requireGroup(waitlistId, groupId);
        List<MemberView> out = new ArrayList<>();
        for (GroupRepository.Member m : groups.members(groupId)) {
            // STRICT members share the creator's entry; PARTIAL members each have their own.
            var entry = "STRICT".equals(w.groupPolicy())
                    ? entries.findByGroup(waitlistId, groupId)
                    : entries.findActive(waitlistId, m.userId());
            out.add(new MemberView(m.userId(), m.name(), m.confirmed(),
                    entry.map(QueueEntry::id).orElse(null), entry.map(QueueEntry::state).orElse(null)));
        }
        return new GroupView(groupId, waitlistId, w.groupPolicy(), out);
    }

    private void requireGroup(UUID waitlistId, UUID groupId) {
        if (!groups.waitlistOf(groupId).map(waitlistId::equals).orElse(false)) {
            throw ApiException.notFound("GROUP_NOT_FOUND", "No group " + groupId);
        }
    }

    private QueueEntry groupEntry(UUID waitlistId, UUID groupId) {
        return entries.findByGroup(waitlistId, groupId)
                .orElseThrow(() -> ApiException.notFound("GROUP_NOT_FOUND", "Group has no entry"));
    }
}
