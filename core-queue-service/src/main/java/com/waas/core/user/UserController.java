package com.waas.core.user;

import com.waas.core.common.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    public record CreateUserRequest(
            @NotBlank @Size(max = 100) String name,
            @Email @Size(max = 255) String email) {}

    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AppUser create(@Valid @RequestBody CreateUserRequest request) {
        // Email is optional for demo users: generate a unique placeholder.
        String email = request.email() != null
                ? request.email()
                : request.name().toLowerCase().replaceAll("[^a-z0-9]", "") + "+" + UUID.randomUUID() + "@demo.local";
        try {
            return users.create(email, request.name());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("EMAIL_TAKEN", "A user with that email already exists");
        }
    }

    @GetMapping("/{userId}")
    public AppUser get(@PathVariable UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "No user " + userId));
    }
}
