package dev.notypie.application.configurations

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Globally enables Spring's `@Scheduled` support so scheduler beans (`StandupScheduler`,
 * future reminders) run regardless of which outbox-reading strategy is active. Previously,
 * `@EnableScheduling` lived only on `PoolingPublisherConfig`, which Spring loads only when
 * `slack.app.mode.outbox-reading-strategy = polling`. Under CDC mode the polling config was
 * skipped and `@Scheduled` annotations elsewhere silently became no-ops.
 *
 * Spring tolerates `@EnableScheduling` declared in multiple configurations; the redundant
 * declaration on `PoolingPublisherConfig` is left in place so polling-only deployments keep
 * working without depending on this file.
 */
@Configuration
@EnableScheduling
class SchedulingConfig
