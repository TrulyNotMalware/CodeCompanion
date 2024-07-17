package dev.notypie.domain.command.dto.interactions

data class User(
    val id: String,
    val username: String,
    val name: String,
    val teamId: String
)