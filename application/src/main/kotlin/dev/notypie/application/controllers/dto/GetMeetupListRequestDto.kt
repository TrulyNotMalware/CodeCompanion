package dev.notypie.application.controllers.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime
import javax.validation.constraints.Pattern

/**
 * Data Transfer Object for requesting the list of meetups associated with a Slack user.
 *
 * This DTO includes the Slack user ID and an optional date range to filter the meetups.
 * By default, the start date is set to the current date and time, and the end date is set to one week after the current date.
 * It ensures that the Slack user ID adheres to the expected format.
 *
 * @property userId The Slack user ID. It must start with the letter 'U', followed by alphanumeric uppercase characters.
 * @property startDate The starting date and time for filtering the list of meetups. Defaults to the current date and time.
 * @property endDate The ending date and time for filtering the list of meetups. Defaults to one week after the current date and time.
 */
@Schema(
    name = "GetMeetupListRequestDto",
    description = "Fetch my meeting list",
)
data class GetMeetupListRequestDto(
    @field:Schema(
        description = "slack's user id value",
        example = "U0123456789",
        required = true,
        pattern = "^U[0-9A-Z]+$",
    )
    @field:Pattern(regexp = "^U[0-9A-Z]+$", message = "slack's user id value must start with U")
    val userId: String,
    @field:Schema(
        description = "start date and time",
        example = "2025-08-05T10:00:00",
        required = false,
        defaultValue = "now",
    )
    val startDate: LocalDateTime = LocalDateTime.now(),
    @field:Schema(
        description = "end date and time",
        example = "2025-08-12T10:00:00",
        required = false,
        defaultValue = "one week after now",
    )
    val endDate: LocalDateTime = LocalDateTime.now().plusWeeks(1L),
)
