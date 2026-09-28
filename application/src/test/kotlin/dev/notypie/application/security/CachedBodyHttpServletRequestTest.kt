package dev.notypie.application.security

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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

                val wrappedRequest = CachedBodyHttpServletRequest.cacheWithinLimit(request = request).shouldNotBeNull()

                then("raw body should remain readable") {
                    String(wrappedRequest.inputStream.readAllBytes(), Charsets.UTF_8) shouldBe rawBody
                    String(wrappedRequest.inputStream.readAllBytes(), Charsets.UTF_8) shouldBe rawBody
                }

                then("form parameters should remain available") {
                    wrappedRequest.getParameter("payload") shouldBe "hello world"
                    wrappedRequest.getParameterValues("payload")?.toList() shouldBe listOf("hello world", "second")
                    wrappedRequest.getParameter("empty") shouldBe ""
                }

                then("the unsigned query string is ignored") {
                    wrappedRequest.getParameter("query").shouldBeNull()
                    wrappedRequest.getParameterValues("query").shouldBeNull()
                    wrappedRequest.parameterMap.keys shouldBe setOf("payload", "empty")
                    wrappedRequest.parameterNames.toList() shouldBe listOf("payload", "empty")
                    wrappedRequest.queryString.shouldBeNull()
                }
            }

            `when`("the body is exactly at the limit") {
                val request =
                    MockHttpServletRequest().apply {
                        setContent(ByteArray(CachedBodyHttpServletRequest.MAX_BODY_BYTES))
                    }

                then("it is cached in full") {
                    CachedBodyHttpServletRequest
                        .cacheWithinLimit(request = request)
                        .shouldNotBeNull()
                        .body.size shouldBe CachedBodyHttpServletRequest.MAX_BODY_BYTES
                }
            }

            `when`("Content-Length declares more than the limit") {
                val request =
                    MockHttpServletRequest().apply {
                        setContent(ByteArray(CachedBodyHttpServletRequest.MAX_BODY_BYTES + 1))
                    }

                then("it is refused") {
                    CachedBodyHttpServletRequest.cacheWithinLimit(request = request).shouldBeNull()
                }
            }

            `when`("a chunked body without Content-Length exceeds the limit") {
                val request =
                    object : MockHttpServletRequest() {
                        override fun getContentLengthLong(): Long = -1L
                    }.apply { setContent(ByteArray(CachedBodyHttpServletRequest.MAX_BODY_BYTES + 1)) }

                then("the bounded read refuses it") {
                    CachedBodyHttpServletRequest.cacheWithinLimit(request = request).shouldBeNull()
                }
            }
        }
    })
