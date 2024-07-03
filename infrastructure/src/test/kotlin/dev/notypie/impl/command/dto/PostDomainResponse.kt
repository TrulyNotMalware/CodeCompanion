package dev.notypie.impl.command.dto

// Reference from https://jsonplaceholder.typicode.com/posts
data class PostDomainResponse(
    val userId: Int,
    val id: Int,
    val title: String,
    val body: String
)