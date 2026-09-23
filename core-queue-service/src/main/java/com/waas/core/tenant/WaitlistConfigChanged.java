package com.waas.core.tenant;

import java.util.UUID;

/** In-process event: a waitlist's capacity or window changed (see {@code WaitlistService.updateConfig}). */
public record WaitlistConfigChanged(UUID waitlistId) {}
