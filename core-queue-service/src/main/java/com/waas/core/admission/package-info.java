/**
 * <b>Admission</b> — rate limiting and idempotency-key middleware (ARCHITECTURE.md §12.1).
 * <p>Runs in front of controllers. Documented extraction trigger: move to an edge
 * service once rejection itself costs meaningful Core capacity (§21.12).
 */
package com.waas.core.admission;
