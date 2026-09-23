package com.waas.core.user;

import java.util.UUID;

public record AppUser(UUID id, String email, String name) {}
