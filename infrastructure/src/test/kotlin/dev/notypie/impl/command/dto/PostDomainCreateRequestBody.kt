package dev.notypie.impl.command.dto

data class PostDomainCreateRequestBody(
    val title: String,
    val body: String,
    val userId: Int
){
    companion object{
        // Reference from https://jsonplaceholder.typicode.com/guide/
        fun getDefault() = PostDomainCreateRequestBody(
            title = "foo", body = "bar", userId = 1
        )
    }
}