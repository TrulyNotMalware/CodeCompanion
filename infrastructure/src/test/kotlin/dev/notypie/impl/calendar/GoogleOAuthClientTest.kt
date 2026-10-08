package dev.notypie.impl.calendar

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

class GoogleOAuthClientTest :
    BehaviorSpec({
        lateinit var respond: (HttpExchange) -> Unit
        var capturedPath = ""
        var capturedBody = ""
        var capturedContentType: String? = null

        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    capturedPath = exchange.requestURI.path
                    capturedContentType = exchange.requestHeaders.getFirst("Content-Type")
                    capturedBody = exchange.requestBody.readAllBytes().decodeToString()
                    respond(exchange)
                }
                start()
            }
        afterSpec { server.stop(0) }

        val baseUrl = "http://127.0.0.1:${server.address.port}"
        val client =
            GoogleOAuthClient(
                clientId = "client-id.apps.googleusercontent.com",
                clientSecret = "client-secret",
                redirectUri = "https://bot.example.com/oauth/google/callback",
                requestTimeout = Duration.ofSeconds(5L),
                authorizationEndpoint = "$baseUrl/auth",
                tokenEndpoint = "$baseUrl/token",
                revokeEndpoint = "$baseUrl/revoke",
            )

        fun response(status: Int, body: String): (HttpExchange) -> Unit =
            { exchange ->
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

        fun formFields(body: String): Map<String, String> =
            body.split("&").associate { pair ->
                val (name, value) = pair.split("=", limit = 2)
                URLDecoder.decode(name, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
            }

        fun idToken(email: String): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val header = encoder.encodeToString("""{"alg":"RS256"}""".toByteArray())
            val payload =
                encoder.encodeToString(
                    """{"sub":"10769150350006150715113082367","email":"$email"}""".toByteArray(),
                )
            return "$header.$payload.signature"
        }

        given("the authorization URL") {
            val url = client.authorizationUrl(state = "st ate&x")

            then("it carries the offline/consent flags, the scopes and the percent-encoded state") {
                url shouldStartWith "$baseUrl/auth?"
                url shouldContain "client_id=client-id.apps.googleusercontent.com"
                url shouldContain "redirect_uri=https%3A%2F%2Fbot.example.com%2Foauth%2Fgoogle%2Fcallback"
                url shouldContain "response_type=code"
                url shouldContain "access_type=offline"
                url shouldContain "prompt=consent"
                url shouldContain "state=st%20ate%26x"
                url shouldContain "scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fcalendar.events%20openid%20email"
                url shouldNotContain "+"
            }
        }

        given("a successful code exchange") {
            respond =
                response(
                    status = 200,
                    body =
                        """{"access_token":"ya29.access","refresh_token":"1//refresh","expires_in":3599,""" +
                            """"id_token":"${idToken(email = "dev@example.com")}",""" +
                            """"scope":"openid https://www.googleapis.com/auth/calendar.events email","token_type":"Bearer"}""",
                )
            val grant = client.exchangeCode(code = "4/0Acode")

            then("the grant carries both tokens and the email read from the id_token") {
                grant.accessToken shouldBe "ya29.access"
                grant.refreshToken shouldBe "1//refresh"
                grant.expiresInSeconds shouldBe 3599L
                grant.email shouldBe "dev@example.com"
                grant.subject shouldBe "10769150350006150715113082367"
                grant.scopes shouldBe setOf("openid", GoogleOAuthClient.CALENDAR_EVENTS_SCOPE, "email")
                grant.grants(scope = GoogleOAuthClient.CALENDAR_EVENTS_SCOPE) shouldBe true
                grant.toString() shouldNotContain "ya29.access"
                grant.toString() shouldNotContain "1//refresh"
            }

            then("the request is a form POST with the authorization_code grant and the client credentials") {
                capturedPath shouldBe "/token"
                capturedContentType shouldBe "application/x-www-form-urlencoded"
                formFields(capturedBody) shouldBe
                    mapOf(
                        "code" to "4/0Acode",
                        "client_id" to "client-id.apps.googleusercontent.com",
                        "client_secret" to "client-secret",
                        "redirect_uri" to "https://bot.example.com/oauth/google/callback",
                        "grant_type" to "authorization_code",
                    )
            }
        }

        given("a code exchange without an id_token whose scope field lacks calendar access") {
            respond =
                response(
                    status = 200,
                    body = """{"access_token":"a","refresh_token":"r","expires_in":10,"scope":"openid"}""",
                )

            then("subject and email are absent and the calendar scope is not granted") {
                val grant = client.exchangeCode(code = "c")
                grant.email.shouldBeNull()
                grant.subject.shouldBeNull()
                grant.scopes shouldBe setOf("openid")
                grant.grants(scope = GoogleOAuthClient.CALENDAR_EVENTS_SCOPE) shouldBe false
            }
        }

        given("a code exchange whose response has no scope field at all") {
            respond = response(status = 200, body = """{"access_token":"a","refresh_token":"r","expires_in":10}""")

            then("the requested scopes are assumed granted, as RFC 6749 §5.1 defines an omitted scope") {
                val grant = client.exchangeCode(code = "c")
                grant.scopes shouldBe GoogleOAuthClient.SCOPES.split(" ").toSet()
                grant.grants(scope = GoogleOAuthClient.CALENDAR_EVENTS_SCOPE) shouldBe true
            }
        }

        given("a code exchange that returns no refresh_token") {
            respond = response(status = 200, body = """{"access_token":"a","expires_in":10}""")

            then("it fails so the connection is never stored without a long-lived credential") {
                shouldThrow<GoogleOAuthException> { client.exchangeCode(code = "c") }.message shouldContain
                    "refresh_token"
            }
        }

        given("a code exchange Google rejects") {
            respond = response(status = 400, body = """{"error":"invalid_grant","error_description":"Bad Request"}""")

            then("the failure carries Google's error code and HTTP status") {
                val failure = shouldThrow<GoogleOAuthException> { client.exchangeCode(code = "stale") }
                failure.message shouldContain "invalid_grant"
                failure.statusCode shouldBe 400
            }
        }

        given("a code exchange that answers with non-JSON") {
            respond = { exchange ->
                val bytes = "<html>gateway timeout</html>".toByteArray()
                exchange.sendResponseHeaders(504, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            then("it fails without throwing a parser error") {
                shouldThrow<GoogleOAuthException> { client.exchangeCode(code = "c") }.statusCode shouldBe 504
            }
        }

        given("token revocation") {
            `when`("Google answers 200") {
                respond = response(status = 200, body = "{}")

                then("it reports success and posted the token as a form field") {
                    client.revoke(token = "1//refresh") shouldBe true
                    capturedPath shouldBe "/revoke"
                    formFields(capturedBody) shouldBe mapOf("token" to "1//refresh")
                }
            }

            `when`("Google answers 400 because the token is already invalid") {
                respond = response(status = 400, body = """{"error":"invalid_token"}""")

                then("it still reports success — nothing is left to revoke") {
                    client.revoke(token = "1//gone") shouldBe true
                }
            }

            `when`("Google answers 400 with another error code") {
                respond = response(status = 400, body = """{"error":"invalid_request"}""")

                then("it reports failure, so the user gets the manual-removal hint") {
                    client.revoke(token = "1//refresh") shouldBe false
                }
            }

            `when`("Google answers 400 with a non-JSON body") {
                respond = { exchange ->
                    val bytes = "<html>bad request</html>".toByteArray()
                    exchange.sendResponseHeaders(400, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }

                then("it reports failure") {
                    client.revoke(token = "1//refresh") shouldBe false
                }
            }

            `when`("Google answers 500") {
                respond = response(status = 500, body = "{}")

                then("it reports failure") {
                    client.revoke(token = "1//refresh") shouldBe false
                }
            }
        }
    })
