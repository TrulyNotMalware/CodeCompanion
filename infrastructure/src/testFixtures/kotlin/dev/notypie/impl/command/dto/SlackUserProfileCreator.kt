package dev.notypie.impl.command.dto

import dev.notypie.domain.TEST_USER_ID

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

fun createUserProfileResponseJson(displayName: String = "testuser", email: String? = "test@example.com"): String {
    val emailField = email?.let { "\"email\": \"$it\", " }.orEmpty()
    return """
        {
          "ok": true,
          "profile": {
            "title": "", "phone": "", "skype": "",
            "real_name": "Test User", "real_name_normalized": "Test User",
            "display_name": "$displayName", "display_name_normalized": "$displayName",
            "fields": {"Xf01": {"value": "Backend", "alt": ""}},
            "status_text": "", "status_emoji": "", "status_emoji_display_info": [], "status_expiration": 0,
            "avatar_hash": "abc123", $emailField"first_name": "Test", "last_name": "User",
            "image_24": "https://example.com/img24.png", "image_32": "https://example.com/img32.png",
            "image_48": "https://example.com/img48.png", "image_72": "https://example.com/img72.png",
            "image_192": "https://example.com/img192.png", "image_512": "https://example.com/img512.png",
            "image_1024": "https://example.com/img1024.png", "image_original": "https://example.com/img.png",
            "is_custom_image": true, "pronouns": "", "huddle_state": "default_unset", "huddle_state_expiration_ts": 0,
            "status_text_canonical": "", "team": "T0001"
          }
        }
        """.trimIndent()
}

fun createUserInfoResponseJson(userId: String = TEST_USER_ID, email: String? = "test@example.com"): String {
    val emailField = email?.let { "\"email\": \"$it\", " }.orEmpty()
    return """
        {
          "ok": true,
          "user": {
            "id": "$userId", "team_id": "T0001", "name": "testuser", "deleted": false, "color": "9f69e7",
            "real_name": "Test User", "tz": "Asia/Seoul", "tz_label": "Korea Standard Time", "tz_offset": 32400,
            "profile": {
              "avatar_hash": "abc123", "status_text": "", "status_emoji": "", "real_name": "Test User",
              "display_name": "testuser", "real_name_normalized": "Test User", "display_name_normalized": "testuser",
              $emailField"image_24": "https://example.com/img24.png", "team": "T0001"
            },
            "is_admin": false, "is_owner": false, "is_primary_owner": false, "is_restricted": false,
            "is_ultra_restricted": false, "is_bot": false, "updated": 1502138686, "is_app_user": false
          }
        }
        """.trimIndent()
}

fun createUserInfoErrorJson(error: String = "user_not_found"): String = """{"ok": false, "error": "$error"}"""
