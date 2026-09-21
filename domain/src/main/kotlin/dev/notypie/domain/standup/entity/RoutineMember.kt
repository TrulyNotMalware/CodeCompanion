package dev.notypie.domain.standup.entity

import dev.notypie.domain.common.validate
import java.time.ZoneId

class RoutineMember(
    val userId: String,
    val userTimezone: ZoneId,
) {
    init {
        validate(className = this.javaClass.simpleName) {
            notBlank {
                "userId" of userId
            }
        }
    }
}
