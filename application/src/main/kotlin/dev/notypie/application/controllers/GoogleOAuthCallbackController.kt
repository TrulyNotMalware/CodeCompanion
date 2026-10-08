package dev.notypie.application.controllers

import dev.notypie.application.configurations.conditions.OnGoogleCalendarEnabled
import dev.notypie.application.service.calendar.CalendarConnectionCallback
import dev.notypie.application.service.calendar.CalendarConnectionOutcome
import org.springframework.context.annotation.Conditional
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@Conditional(OnGoogleCalendarEnabled::class)
class GoogleOAuthCallbackController(
    private val calendarConnectionCallback: CalendarConnectionCallback,
) {
    companion object {
        const val CALLBACK_PATH: String = "/oauth/google/callback"
    }

    @GetMapping(value = [CALLBACK_PATH], produces = [MediaType.TEXT_HTML_VALUE])
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
    ): ResponseEntity<String> {
        val outcome = calendarConnectionCallback.completeConnection(code = code, state = state, error = error)
        val page =
            when (outcome) {
                CalendarConnectionOutcome.CONNECTED ->
                    CallbackPage(
                        status = HttpStatus.OK,
                        title = "Google Calendar connected",
                        detail = "You can close this tab and go back to Slack.",
                    )

                CalendarConnectionOutcome.DENIED ->
                    CallbackPage(
                        status = HttpStatus.OK,
                        title = "Connection cancelled",
                        detail = "No access was granted. Run /calendar connect in Slack to try again.",
                    )

                CalendarConnectionOutcome.SCOPE_DENIED ->
                    CallbackPage(
                        status = HttpStatus.OK,
                        title = "Calendar access was not allowed",
                        detail =
                            "Nothing was connected. Run /calendar connect in Slack again and " +
                                "allow access to your calendar events.",
                    )

                CalendarConnectionOutcome.INVALID_STATE ->
                    CallbackPage(
                        status = HttpStatus.BAD_REQUEST,
                        title = "This link is no longer valid",
                        detail =
                            "It may have expired or already been used. " +
                                "Run /calendar connect in Slack for a new one.",
                    )

                CalendarConnectionOutcome.EXCHANGE_FAILED ->
                    CallbackPage(
                        status = HttpStatus.BAD_GATEWAY,
                        title = "Google did not complete the connection",
                        detail = "Run /calendar connect in Slack to try again.",
                    )

                CalendarConnectionOutcome.STORE_FAILED ->
                    CallbackPage(
                        status = HttpStatus.SERVICE_UNAVAILABLE,
                        title = "The connection could not be saved",
                        detail =
                            "Nothing was connected. This link cannot be used again; " +
                                "run /calendar connect in Slack for a new one.",
                    )
            }
        return ResponseEntity
            .status(page.status)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentType(MediaType.TEXT_HTML)
            .body(page.render())
    }

    private data class CallbackPage(
        val status: HttpStatus,
        val title: String,
        val detail: String,
    )

    private fun CallbackPage.render(): String =
        """
        <!doctype html>
        <html lang="en">
        <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>$title</title></head>
        <body style="font-family: sans-serif; margin: 3rem auto; max-width: 32rem; padding: 0 1rem;">
        <h1>$title</h1>
        <p>$detail</p>
        </body>
        </html>
        """.trimIndent()
}
