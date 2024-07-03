package dev.notypie.impl.command.dto

data class PostDomainUpdateRequestBody(
    val title: String,
    val body: String,
    val userId: Int,
    val id: Int
){
    companion object{
        // Reference from https://jsonplaceholder.typicode.com/guide/
        fun getDefault() = PostDomainUpdateRequestBody(
            title = "foo", body = "bar", userId = 1, id = 1
        )
    }
}