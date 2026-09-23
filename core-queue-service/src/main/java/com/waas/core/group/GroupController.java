package com.waas.core.group;

import com.waas.core.group.GroupService.GroupView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import com.waas.core.auth.CurrentUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/waitlists/{waitlistId}/groups")
public class GroupController {

    public record CreateGroupRequest(@NotNull List<UUID> memberIds) {}

    private final GroupService groups;

    public GroupController(GroupService groups) {
        this.groups = groups;
    }

    /** The caller creates the group and is always a member. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupView create(@PathVariable UUID waitlistId, @CurrentUser UUID userId,
                            @Valid @RequestBody CreateGroupRequest request) {
        return groups.create(waitlistId, userId, request.memberIds());
    }

    @GetMapping("/{groupId}")
    public GroupView get(@PathVariable UUID waitlistId, @PathVariable UUID groupId) {
        return groups.view(waitlistId, groupId);
    }

    @PostMapping("/{groupId}/confirm")
    public GroupView confirm(@PathVariable UUID waitlistId, @PathVariable UUID groupId,
                             @CurrentUser UUID userId) {
        return groups.confirm(waitlistId, groupId, userId);
    }
}
