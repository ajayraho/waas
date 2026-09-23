package com.waas.core.auth;

import com.waas.core.common.error.ApiException;
import com.waas.core.user.AppUser;
import com.waas.core.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record RegisterRequest(@NotBlank @Size(max = 100) String name, @NotBlank @Email String email,
                                  @NotBlank @Size(min = 6, max = 100) String password) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record GuestRequest(@NotBlank @Size(max = 100) String name) {}

    public record TokenResponse(String token, AppUser user) {}

    private static final String NO_PASSWORD = "guest";

    private final UserRepository users;
    private final JwtService jwt;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

    public AuthController(UserRepository users, JwtService jwt) {
        this.users = users;
        this.jwt = jwt;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest r) {
        try {
            AppUser u = users.create(r.email().toLowerCase(), r.name(), passwords.encode(r.password()));
            return new TokenResponse(jwt.issue(u.id(), u.name()), u);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("EMAIL_TAKEN", "That email is already registered");
        }
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest r) {
        var found = users.findWithHash(r.email().toLowerCase());
        if (found.isEmpty() || NO_PASSWORD.equals(found.get().passwordHash())
                || !passwords.matches(r.password(), found.get().passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Wrong email or password");
        }
        AppUser u = found.get().user();
        return new TokenResponse(jwt.issue(u.id(), u.name()), u);
    }

    /** Demo shortcut: a throwaway account with no password (can't be logged into later). */
    @PostMapping("/guest")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse guest(@Valid @RequestBody GuestRequest r) {
        AppUser u = users.create("guest+" + UUID.randomUUID() + "@demo.local", r.name(), NO_PASSWORD);
        return new TokenResponse(jwt.issue(u.id(), u.name()), u);
    }

    @GetMapping("/me")
    public AppUser me(@CurrentUser UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "No user"));
    }
}
