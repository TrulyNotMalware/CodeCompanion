package dev.notypie.impl.command

import dev.notypie.dto.PostDomainCreateRequestBody
import dev.notypie.dto.PostDomainResponse
import dev.notypie.dto.PostDomainUpdateRequestBody
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClientException

class RestClientRequesterTest :
    BehaviorSpec({
        val restRequester: RestRequester =
            RestClientRequester(
                baseUrl = "https://jsonplaceholder.typicode.com/posts",
            )
        val createRequestBody = PostDomainCreateRequestBody.getDefault()
        val updateRequestBody = PostDomainUpdateRequestBody.getDefault()

        given("Rest API Requester") {
            `when`("get request") {
                then("successfully works") {
                    val response =
                        restRequester
                            .safeGet(
                                uri = "/1",
                                authorizationHeader = null,
                                responseType = PostDomainResponse::class.java,
                            ).getOrThrow()
                    val responseList =
                        restRequester
                            .safeGet(
                                uri = "",
                                authorizationHeader = null,
                                responseType = Array<PostDomainResponse>::class.java,
                            ).getOrThrow()
                    response.statusCode shouldBe HttpStatus.OK
                    response.body shouldNotBe null

                    responseList.statusCode shouldBe HttpStatus.OK
                    responseList.body shouldNotBe null
                    responseList.body?.size shouldNotBe 0
                }
            }

            `when`("post request") {
                then("successfully works") {
                    val response =
                        restRequester
                            .safePost(
                                uri = "",
                                authorizationHeader = null,
                                contentType = MediaType.APPLICATION_JSON,
                                body = createRequestBody,
                                responseType = PostDomainResponse::class.java,
                            ).getOrThrow()
                    response.statusCode shouldBe HttpStatus.CREATED
                    response.body?.apply {
                        userId shouldBe createRequestBody.userId
                        body shouldBe createRequestBody.body
                    }
                }

                then("throws exceptions when uri is invalid") {
                    shouldThrowExactly<RestClientException> {
                        restRequester.post(
                            uri = "/1",
                            authorizationHeader = null,
                            contentType = MediaType.APPLICATION_JSON,
                            body = createRequestBody,
                            responseType = PostDomainResponse::class.java,
                        )
                    }
                }
            }

            `when`("update request") {
                then("successfully works") {
                    val putRequest =
                        restRequester
                            .safePut(
                                uri = "/1",
                                authorizationHeader = null,
                                contentType = MediaType.APPLICATION_JSON,
                                body = updateRequestBody,
                                responseType = PostDomainResponse::class.java,
                            ).getOrThrow()
                    val patchResponse =
                        restRequester
                            .safePatch(
                                uri = "/1",
                                authorizationHeader = null,
                                contentType = MediaType.APPLICATION_JSON,
                                body = updateRequestBody,
                                responseType = PostDomainResponse::class.java,
                            ).getOrThrow()

                    with(putRequest) {
                        statusCode shouldBe HttpStatus.OK
                        body shouldNotBe null
                        body?.apply {
                            userId shouldBe updateRequestBody.userId
                            id shouldBe updateRequestBody.id
                            title shouldBe updateRequestBody.title
                            body shouldBe updateRequestBody.body
                        }
                    }

                    with(patchResponse) {
                        statusCode shouldBe HttpStatus.OK
                        body shouldNotBe null
                        body?.apply {
                            userId shouldBe updateRequestBody.userId
                            id shouldBe updateRequestBody.id
                            title shouldBe updateRequestBody.title
                            body shouldBe updateRequestBody.body
                        }
                    }
                }
            }

            `when`("delete request") {
                then("successfully works") {
                    val response =
                        restRequester
                            .safeDelete(uri = "/1", authorizationHeader = null, responseType = Void::class.java)
                            .getOrThrow()
                    response.statusCode shouldBe HttpStatus.OK
                    response.body shouldBe null
                }
            }

            `when`("non-safe get request") {
                then("returns body directly") {
                    val response =
                        restRequester.get(
                            uri = "/1",
                            authorizationHeader = null,
                            responseType = PostDomainResponse::class.java,
                        )
                    response shouldNotBe null
                    response.id shouldBe 1
                }
            }

            `when`("non-safe put request") {
                then("returns body directly") {
                    val response =
                        restRequester.put(
                            uri = "/1",
                            authorizationHeader = null,
                            contentType = MediaType.APPLICATION_JSON,
                            body = updateRequestBody,
                            responseType = PostDomainResponse::class.java,
                        )
                    response shouldNotBe null
                    response.userId shouldBe updateRequestBody.userId
                }
            }

            `when`("non-safe patch request") {
                then("returns body directly") {
                    val response =
                        restRequester.patch(
                            uri = "/1",
                            authorizationHeader = null,
                            contentType = MediaType.APPLICATION_JSON,
                            body = updateRequestBody,
                            responseType = PostDomainResponse::class.java,
                        )
                    response shouldNotBe null
                    response.userId shouldBe updateRequestBody.userId
                }
            }

            `when`("non-safe delete request") {
                then("throws exception when response body is null") {
                    shouldThrowExactly<RestClientException> {
                        restRequester.delete(
                            uri = "/1",
                            authorizationHeader = null,
                            responseType = Void::class.java,
                        )
                    }
                }
            }
        }
    })
