package dev.notypie.domain.user

enum class UserRole {
    ROOT, // who contains this application server. only one.
    ADMIN,
    DEVELOPER, // Developer
    MEMBER, // Default Role
}