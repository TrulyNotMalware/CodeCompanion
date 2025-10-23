package dev.notypie.domain

import dev.notypie.domain.command.dto.interactions.Channel
import dev.notypie.domain.command.dto.interactions.Team
import dev.notypie.domain.command.dto.interactions.User

const val TEST_APP_ID = "A12ABCDEFG" // starts with A
const val TEST_USER_ID = "U012ABCDEFG" // starts with U
const val TEST_USER_NAME = "I_AM_TEST_USER"
const val TEST_CHANNEL_ID = "C012ABCDEFG" // starts with C
const val TEST_CHANNEL_NAME = "I_AM_TEST_CHANNEL"
const val TEST_TOKEN = "I_AM_TEST_TOKEN"
const val TEST_TEAM_ID = "T012ABCDEFG" // starts with T
const val TEST_TEAM_DOMAIN = "I_AM_TEST_TEAM_DOMAIN"
const val TEST_BOT_ID = "B012ABCDEFG"
const val TEST_BASE_URL = "https://hooks.example.com/actions"
val TEST_USER = User(id = TEST_USER_ID, userName = TEST_USER_NAME, name = TEST_USER_NAME, teamId = TEST_TEAM_ID)
val TEST_TEAM = Team(id = TEST_TEAM_ID, domain = TEST_TEAM_DOMAIN)
val TEST_CHANNEL = Channel(id = TEST_CHANNEL_ID, name = TEST_CHANNEL_NAME)
