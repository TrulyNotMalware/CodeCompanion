package dev.notypie.impl.command

import dev.notypie.impl.command.dto.PostDomainCreateRequestBody
import dev.notypie.impl.command.dto.PostDomainResponse
import dev.notypie.impl.command.dto.PostDomainUpdateRequestBody
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.HttpClientErrorException

class RestClientRequesterTest: BehaviorSpec({
    val restRequester: RestRequester = RestClientRequester(
        baseUrl = "https://jsonplaceholder.typicode.com/posts"
    )
    val createRequestBody = PostDomainCreateRequestBody.getDefault()
    val updateRequestBody = PostDomainUpdateRequestBody.getDefault()

    given("Rest API Requester"){
        `when`("get request"){
            then("successfully works"){
                val response = restRequester.get(uri = "/1", authorizationHeader = null, responseType = PostDomainResponse::class.java)
                val responseList = restRequester.get(uri = "", authorizationHeader = null, responseType = List::class.java)
                response.statusCode shouldBe HttpStatus.OK
                response.body shouldNotBe null

                responseList.statusCode shouldBe HttpStatus.OK
                responseList.body shouldNotBe null
                responseList.body?.size shouldNotBe 0
            }

        }

        `when`("post request"){
            then("successfully works"){
                val response = restRequester.post(uri = "", authorizationHeader = null, contentType = MediaType.APPLICATION_JSON, body = createRequestBody, responseType = PostDomainResponse::class.java)
                response.statusCode shouldBe HttpStatus.CREATED
                response.body?.apply {
                    userId shouldBe createRequestBody.userId
                    body shouldBe createRequestBody.body
                }
            }

            then("throws exceptions when uri is invalid"){
                shouldThrowExactly<HttpClientErrorException.NotFound> {
                    restRequester.post(uri = "/1" , authorizationHeader = null, contentType = MediaType.APPLICATION_JSON, body = createRequestBody, responseType = PostDomainResponse::class.java)
                }
            }
        }

        `when`("update request"){
            then("successfully works"){
                val putRequest = restRequester.put(uri = "/1", authorizationHeader = null, contentType = MediaType.APPLICATION_JSON, body = updateRequestBody, responseType = PostDomainResponse::class.java)
                val patchResponse = restRequester.patch(uri = "/1", authorizationHeader = null, contentType = MediaType.APPLICATION_JSON, body = updateRequestBody, responseType = PostDomainResponse::class.java)

                with (putRequest) {
                    statusCode shouldBe HttpStatus.OK
                    body shouldNotBe null
                    body?.apply {
                        userId shouldBe updateRequestBody.userId
                        id shouldBe updateRequestBody.id
                        title shouldBe updateRequestBody.title
                        body shouldBe updateRequestBody.body
                    }
                }

                with (patchResponse) {
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

        `when`("delete request"){
            then("successfully works"){
                val response = restRequester.delete(uri = "/1", authorizationHeader = null, responseType = Void::class.java)
                response.statusCode shouldBe HttpStatus.OK
                response.body shouldBe null
            }
        }
    }
})