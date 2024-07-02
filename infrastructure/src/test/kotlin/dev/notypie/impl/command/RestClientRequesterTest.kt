package dev.notypie.impl.command

import io.kotest.core.spec.style.BehaviorSpec

class RestClientRequesterTest(

): BehaviorSpec({
    val restRequester: RestRequester = RestClientRequester(
        baseUrl = "https://jsonplaceholder.typicode.com/posts"
    )

    given("Rest API Requester"){

        `when`("get request"){

        }
    }
})