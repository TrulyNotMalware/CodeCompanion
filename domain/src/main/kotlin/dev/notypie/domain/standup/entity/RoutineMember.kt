package dev.notypie.domain.standup.entity

import dev.notypie.domain.common.validate
import java.time.ZoneId

/**
 * A user enrolled in a [Routine]. Each member carries their own [userTimezone] so DMs go out
 * at *their* local trigger time even when members live in different timezones — that is the
 * core of decision A in the Phase 3 setup ("per-user ZoneId").
 *
 * The timezone is stored as a [ZoneId] (e.g. `Asia/Seoul`, `America/Los_Angeles`); the
 * persistence layer round-trips it as the IANA string. Members default to the routine's
 * timezone when they have not explicitly set their own — handled at the [Routine] level.
 */
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
