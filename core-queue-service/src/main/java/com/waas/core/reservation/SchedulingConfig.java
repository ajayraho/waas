package com.waas.core.reservation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background jobs (sweeper, reconciler) on by default. Integration tests turn them off with
 * {@code waas.scheduling.enabled=false} and call the job methods directly, so a background
 * tick can never race a test's assertions.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "waas.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {}
