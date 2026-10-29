package dev.notypie

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication
class TestApplication {
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}
