package dev.notypie.dto

data class PostDomainCreateRequestBody(
    val title: String,
    val body: String,
    val userId: Int,
) {
    companion object {
        fun getDefault() =
            PostDomainCreateRequestBody(
                title = "foo",
                body = "bar",
                userId = 1,
            )
    }
}
