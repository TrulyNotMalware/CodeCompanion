package dev.notypie.domain.command.entity

enum class CommandType {
    SIMPLE,
    PIPELINE,
    SCHEDULED
}

enum class CommandDetailType {
    NOTHING,
    SIMPLE_TEXT,
    ERROR_RESPONSE,
    APPROVAL_FORM,
    REQUEST_APPLY_FORM,

    REQUEST_MEETING_FORM,
    NOTICE_FORM
}