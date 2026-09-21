package dev.notypie.dto

data class PostDomainResponse(
    val userId: Int,
    val id: Int,
    val title: String,
    val body: String,
)
