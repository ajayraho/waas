/**
 * <b>Tenant / Admin</b> — waitlist configuration CRUD and tenant isolation
 * ({@code WaitlistAccessGuard}, ARCHITECTURE.md §21.10).
 * <p>Low volume. Other domains depend on it only for {@code TenantScopedWaitlist}.
 */
package com.waas.core.tenant;
