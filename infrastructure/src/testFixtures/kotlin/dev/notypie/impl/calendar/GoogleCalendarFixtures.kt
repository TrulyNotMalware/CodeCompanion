package dev.notypie.impl.calendar

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

fun createCalendarEventBody(
    summary: String = "Sprint review",
    description: String = "Demo the release",
    start: LocalDateTime = LocalDateTime.of(2031, 1, 6, 15, 0),
    end: LocalDateTime = LocalDateTime.of(2031, 1, 6, 16, 0),
    timeZone: ZoneId = ZoneId.of("Asia/Seoul"),
    meetingUid: UUID = UUID.fromString("7d2c3f9e-4b1a-4c55-9a8e-0f6b2d1c3e4a"),
) = CalendarEventBody(
    summary = summary,
    description = description,
    start = start,
    end = end,
    timeZone = timeZone,
    meetingUid = meetingUid,
)

fun createGoogleApiErrorJson(code: Int, reason: String, message: String): String =
    """
    {
      "error": {
        "code": $code,
        "message": "$message",
        "errors": [{"domain": "usageLimits", "reason": "$reason", "message": "$message"}]
      }
    }
    """.trimIndent()

fun createGoogleServiceDisabledErrorJson(
    legacyReason: String? = "accessNotConfigured",
    errorInfoReason: String? = "SERVICE_DISABLED",
    status: String = "PERMISSION_DENIED",
    message: String =
        "Google Calendar API has not been used in project 123456 before or it is disabled. Enable it by visiting " +
            "https://console.developers.google.com/apis/api/calendar-json.googleapis.com/overview?project=123456 " +
            "then retry.",
): String {
    val errors =
        legacyReason?.let { """[{"domain": "usageLimits", "reason": "$it", "message": "$message"}]""" } ?: "[]"
    val details =
        errorInfoReason?.let {
            """[{"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "$it", "domain": "googleapis.com", """ +
                """"metadata": {"service": "calendar-json.googleapis.com", "consumer": "projects/123456"}}]"""
        } ?: "[]"
    return """{"error": {"code": 403, "message": "$message", "errors": $errors, "status": "$status", """ +
        """"details": $details}}"""
}
