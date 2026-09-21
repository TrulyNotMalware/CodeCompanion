package dev.notypie.application.configurations

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

// PoolingPublisherConfig also declares @EnableScheduling; Spring allows the duplicate — keep both.
@Configuration
@EnableScheduling
class SchedulingConfig
