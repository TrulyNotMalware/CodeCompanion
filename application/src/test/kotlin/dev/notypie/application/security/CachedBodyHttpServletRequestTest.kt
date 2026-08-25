package dev.notypie.application.security

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest

class CachedBodyHttpServletRequestTest :
    BehaviorSpec({
        given("CachedBodyHttpServletRequest") {
            `when`("wrapping a form-urlencoded request") {
                val rawBody = "payload=hello+world&payload=second&empty="
                val request =
                    MockHttpServletRequest().apply {
                        method = "POST"
                        requestURI = "/api/slack/interaction"
                        queryString = "query=ok"
                        contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE
                        characterEncoding = Charsets.UTF_8.name()
                        setContent(rawBody.toByteArray(Charsets.UTF_8))
                    }

                val wrappedRequest = CachedBodyHttpServletRequest(request = request)

                then("raw body should remain readable") {
                    String(wrappedRequest.inputStream.readAllBytes(), Charsets.UTF_8) shouldBe rawBody
                    String(wrappedRequest.inputStream.readAllBytes(), Charsets.UTF_8) shouldBe rawBody
                }

                then("form parameters should remain available") {
                    wrappedRequest.getParameter("payload") shouldBe "hello world"
                    wrappedRequest.getParameterValues("payload")?.toList() shouldBe listOf("hello world", "second")
                    wrappedRequest.getParameter("empty") shouldBe ""
                    wrappedRequest.getParameter("query") shouldBe "ok"
                }
            }
        }
    })
