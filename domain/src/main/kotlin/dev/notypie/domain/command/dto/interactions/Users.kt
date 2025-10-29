package dev.notypie.domain.command.dto.interactions

data class User(
    val id: String,
    val userName: String,
    val name: String,
    val teamId: String,
)

data class Team(
    val id: String,
    val domain: String,
)
