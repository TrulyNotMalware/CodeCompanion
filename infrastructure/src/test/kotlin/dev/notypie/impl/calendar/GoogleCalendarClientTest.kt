package dev.notypie.impl.calendar

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.notypie.common.jsonMapper
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration

class GoogleCalendarClientTest :
    BehaviorSpec({
        lateinit var respond: (HttpExchange) -> Unit
        var capturedMethod = ""
        var capturedPath = ""
        var capturedAuthorization: String? = null
        var capturedContentType: String? = null
        var capturedBody = ""

        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    capturedMethod = exchange.requestMethod
                    capturedPath = exchange.requestURI.rawPath
                    capturedAuthorization = exchange.requestHeaders.getFirst("Authorization")
                    capturedContentType = exchange.requestHeaders.getFirst("Content-Type")
                    capturedBody = exchange.requestBody.readAllBytes().decodeToString()
                    respond(exchange)
                }
                start()
            }
        afterSpec { server.stop(0) }

        val baseUrl = "http://127.0.0.1:${server.address.port}/calendar/v3"
        val client = GoogleCalendarClient(requestTimeout = Duration.ofSeconds(5L), baseUrl = baseUrl)
        val event = createCalendarEventBody()
        val mirroredId = "5a93d5c4725fb62f61c010c223433adb2bc4827760944208f1d62debba03f933"

        fun response(status: Int, body: String, headers: Map<String, String> = emptyMap()): (HttpExchange) -> Unit =
            { exchange ->
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
                exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

        given("an insert Google accepts") {
            respond =
                response(status = 200, body = """{"id":"$mirroredId","status":"confirmed"}""")
            val result = client.insert(accessToken = "ya29.access", eventId = mirroredId, event = event)

            then("the created event id comes back") {
                result shouldBe CalendarApiResult.Ok(eventId = mirroredId)
            }

            then("the request is a bearer-authorized JSON POST to the primary calendar's events") {
                capturedMethod shouldBe "POST"
                capturedPath shouldBe "/calendar/v3/calendars/primary/events"
                capturedAuthorization shouldBe "Bearer ya29.access"
                capturedContentType shouldBe "application/json"
            }

            then("the body carries the caller's event id, so a repeated insert of the same meeting is refused") {
                jsonMapper.readTree(capturedBody).path("id").asString() shouldBe mirroredId
            }

            then("the body carries a confirmed status, title, description, start and end with the zone, and the uid") {
                val body = jsonMapper.readTree(capturedBody)
                body.path("status").asString() shouldBe "confirmed"
                body.path("summary").asString() shouldBe "Sprint review"
                body.path("description").asString() shouldBe "Demo the release"
                body.path("start").path("dateTime").asString() shouldBe "2031-01-06T15:00:00"
                body.path("start").path("timeZone").asString() shouldBe "Asia/Seoul"
                body.path("end").path("dateTime").asString() shouldBe "2031-01-06T16:00:00"
                body.path("end").path("timeZone").asString() shouldBe "Asia/Seoul"
                body
                    .path("extendedProperties")
                    .path("private")
                    .path(GoogleCalendarClient.MEETING_UID_PROPERTY)
                    .asString() shouldBe event.meetingUid.toString()
            }
        }

        given("a patch Google accepts") {
            respond = response(status = 200, body = """{"id":"evt 1/x"}""")
            val result = client.patch(accessToken = "ya29.access", eventId = "evt 1/x", event = event)

            then("it is a PATCH to the percent-encoded event path and returns the event id") {
                result shouldBe CalendarApiResult.Ok(eventId = "evt 1/x")
                capturedMethod shouldBe "PATCH"
                capturedPath shouldBe "/calendar/v3/calendars/primary/events/evt%201%2Fx"
                capturedAuthorization shouldBe "Bearer ya29.access"
                jsonMapper.readTree(capturedBody).path("summary").asString() shouldBe "Sprint review"
            }

            then("the body sets the status to confirmed, which resurrects an event the user deleted") {
                jsonMapper.readTree(capturedBody).path("status").asString() shouldBe "confirmed"
            }

            then("the body does not carry an id: the event is addressed by its path") {
                jsonMapper.readTree(capturedBody).has("id") shouldBe false
            }
        }

        given("a delete") {
            `when`("Google answers 204") {
                respond = response(status = 204, body = "")
                val result = client.delete(accessToken = "ya29.access", eventId = "evt-9")

                then("it is a bodiless DELETE of the event and returns the deleted id") {
                    result shouldBe CalendarApiResult.Ok(eventId = "evt-9")
                    capturedMethod shouldBe "DELETE"
                    capturedPath shouldBe "/calendar/v3/calendars/primary/events/evt-9"
                    capturedAuthorization shouldBe "Bearer ya29.access"
                    capturedContentType.shouldBeNull()
                    capturedBody shouldBe ""
                }
            }

            `when`("Google answers 404 or 410") {
                respond = response(status = 404, body = """{"error":{"code":404,"message":"Not Found"}}""")
                val notFound = client.delete(accessToken = "ya29.access", eventId = "evt-gone")
                respond = response(status = 410, body = """{"error":{"code":410,"message":"Deleted"}}""")
                val deleted = client.delete(accessToken = "ya29.access", eventId = "evt-gone")

                then("both are Gone: the event no longer exists") {
                    notFound shouldBe CalendarApiResult.Gone
                    deleted shouldBe CalendarApiResult.Gone
                }
            }
        }

        given("an insert whose event id already exists in the calendar") {
            respond =
                response(
                    status = 409,
                    body = """{"error":{"code":409,"message":"The requested identifier already exists."}}""",
                )

            then("the result is AlreadyExists, not a failure") {
                client.insert(accessToken = "ya29.access", eventId = mirroredId, event = event) shouldBe
                    CalendarApiResult.AlreadyExists
                capturedMethod shouldBe "POST"
            }
        }

        given("an access token Google no longer accepts") {
            respond = response(status = 401, body = """{"error":{"code":401,"message":"Invalid Credentials"}}""")

            then("the result is Unauthorized") {
                client.insert(accessToken = "ya29.expired", eventId = mirroredId, event = event) shouldBe
                    CalendarApiResult.Unauthorized
            }
        }

        given("a 403 whose reason is a rate limit") {
            respond =
                response(
                    status = 403,
                    body =
                        createGoogleApiErrorJson(
                            code = 403,
                            reason = "userRateLimitExceeded",
                            message = "User Rate Limit Exceeded",
                        ),
                )

            then("it is RateLimited without a delay hint") {
                client.insert(accessToken = "ya29.access", eventId = mirroredId, event = event) shouldBe
                    CalendarApiResult.RateLimited(retryAfter = null)
            }
        }

        given("a 429 with a Retry-After header") {
            respond =
                response(
                    status = 429,
                    body =
                        createGoogleApiErrorJson(
                            code = 429,
                            reason = "rateLimitExceeded",
                            message = "Rate Limit Exceeded",
                        ),
                    headers = mapOf("Retry-After" to "7"),
                )

            then("it is RateLimited with the header read as seconds") {
                client.insert(accessToken = "ya29.access", eventId = mirroredId, event = event) shouldBe
                    CalendarApiResult.RateLimited(retryAfter = Duration.ofSeconds(7L))
            }
        }

        given("a 429 whose Retry-After asks for longer than a day") {
            respond =
                response(
                    status = 429,
                    body =
                        createGoogleApiErrorJson(
                            code = 429,
                            reason = "rateLimitExceeded",
                            message = "Rate Limit Exceeded",
                        ),
                    headers = mapOf("Retry-After" to "999999999999"),
                )

            then("the hint is capped at 24 hours, so one header cannot park a row for years") {
                client.insert(accessToken = "ya29.access", eventId = mirroredId, event = event) shouldBe
                    CalendarApiResult.RateLimited(retryAfter = Duration.ofHours(24L))
            }
        }

        given("a 403 saying the Calendar API is disabled for the OAuth client's project") {
            val googleMessage =
                "Google Calendar API has not been used in project 123456 before or it is disabled. Enable it by " +
                    "visiting https://console.developers.google.com/apis/api/calendar-json.googleapis.com/overview" +
                    "?project=123456 then retry."

            `when`("the body carries both the legacy reason and the ErrorInfo reason, as Google sends it") {
                respond = response(status = 403, body = createGoogleServiceDisabledErrorJson())

                then("it is Misconfigured, naming the reason and keeping Google's message") {
                    client.insert(accessToken = "ya29.access", eventId = mirroredId, event = event) shouldBe
                        CalendarApiResult.Misconfigured(
                            message =
                                "Google Calendar API is not enabled for the OAuth client's project " +
                                    "(accessNotConfigured): $googleMessage",
                        )
                }
            }

            `when`("only the ErrorInfo detail says SERVICE_DISABLED") {
                respond = response(status = 403, body = createGoogleServiceDisabledErrorJson(legacyReason = null))

                then("it is still Misconfigured") {
                    client.patch(accessToken = "ya29.access", eventId = "evt-1", event = event) shouldBe
                        CalendarApiResult.Misconfigured(
                            message =
                                "Google Calendar API is not enabled for the OAuth client's project " +
                                    "(SERVICE_DISABLED): $googleMessage",
                        )
                }
            }

            `when`("only the legacy errors[] entry says accessNotConfigured") {
                respond =
                    response(status = 403, body = createGoogleServiceDisabledErrorJson(errorInfoReason = null))

                then("it is still Misconfigured") {
                    client
                        .delete(accessToken = "ya29.access", eventId = "evt-1")
                        .shouldBeInstanceOf<CalendarApiResult.Misconfigured>()
                }
            }

            `when`("the status is PERMISSION_DENIED but neither reason is present") {
                respond =
                    response(
                        status = 403,
                        body = createGoogleServiceDisabledErrorJson(legacyReason = null, errorInfoReason = null),
                    )

                then("the status alone does not stop the worker: it is an ordinary failure") {
                    client.insert(accessToken = "ya29.access", eventId = mirroredId, event = event) shouldBe
                        CalendarApiResult.Failed(statusCode = 403, message = googleMessage)
                }
            }
        }

        given("a 403 with a reason that is not a rate limit") {
            respond =
                response(
                    status = 403,
                    body =
                        createGoogleApiErrorJson(
                            code = 403,
                            reason = "forbiddenForNonOrganizer",
                            message = "Shared properties can only be changed by the organizer of the event.",
                        ),
                )

            then("it fails with the status and Google's message") {
                client.patch(accessToken = "ya29.access", eventId = "evt-1", event = event) shouldBe
                    CalendarApiResult.Failed(
                        statusCode = 403,
                        message = "Shared properties can only be changed by the organizer of the event.",
                    )
            }
        }

        given("a 500 with an empty error object") {
            respond = response(status = 500, body = "{}")

            then("it fails with the status") {
                client.insert(accessToken = "ya29.access", eventId = mirroredId, event = event) shouldBe
                    CalendarApiResult.Failed(statusCode = 500, message = "HTTP 500")
            }
        }

        given("a 200 insert response") {
            `when`("the body has no id") {
                respond = response(status = 200, body = """{"status":"confirmed"}""")

                then("it fails instead of storing an empty event id") {
                    client.insert(accessToken = "ya29.access", eventId = mirroredId, event = event) shouldBe
                        CalendarApiResult.Failed(statusCode = 200, message = "response has no event id")
                }
            }

            `when`("the body is not JSON") {
                respond = response(status = 200, body = "<html>ok</html>")

                then("it fails without throwing a parser error") {
                    client.insert(accessToken = "ya29.access", eventId = mirroredId, event = event) shouldBe
                        CalendarApiResult.Failed(statusCode = 200, message = "response is not JSON")
                }
            }
        }

        given("a calendar endpoint nobody listens on") {
            val closedPort = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
            val unreachable =
                GoogleCalendarClient(
                    requestTimeout = Duration.ofSeconds(5L),
                    baseUrl = "http://127.0.0.1:$closedPort/calendar/v3",
                )

            then("the transport failure is a Failed result without a status, not an exception") {
                val result = unreachable.delete(accessToken = "ya29.access", eventId = "evt-1")
                result.shouldBeInstanceOf<CalendarApiResult.Failed>().statusCode.shouldBeNull()
            }
        }
    })
