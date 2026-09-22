package dev.notypie.impl.command.dto

fun createProfile(
    displayName: String = "testuser",
    realName: String = "Test User",
    imageSize24: String = "https://example.com/img24.png",
): Profile =
    Profile(
        title = "",
        phone = "",
        skype = "",
        realName = realName,
        realNameNormalized = realName,
        displayName = displayName,
        displayNameNormalized = displayName,
        fields = emptyMap(),
        statusText = "",
        statusEmoji = "",
        statusExpiration = 0,
        avatarHash = "abc123",
        email = "test@example.com",
        firstName = "Test",
        lastName = "User",
        imageSize24 = imageSize24,
        imageSize32 = "https://example.com/img32.png",
        imageSize48 = "https://example.com/img48.png",
        imageSize72 = "https://example.com/img72.png",
        imageSize192 = "https://example.com/img192.png",
        imageSize512 = "https://example.com/img512.png",
        statusTextCanonical = "",
    )
