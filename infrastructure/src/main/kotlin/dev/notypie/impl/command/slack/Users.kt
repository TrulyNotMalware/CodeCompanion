package dev.notypie.impl.command.slack

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
