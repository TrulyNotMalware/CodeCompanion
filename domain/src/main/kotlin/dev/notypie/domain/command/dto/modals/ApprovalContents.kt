package dev.notypie.domain.command.dto.modals

import java.time.LocalDateTime

data class ApprovalContents(
    val type: String,
    val reason: String,

    val approvalResponseUrl: String = "",
    val approvalButtonName: String = "Approval",
    val approvalInteractionValue: String = "",

    val rejectResponseUrl: String = "",
    val rejectButtonName: String = "Deny",
    val rejectInteractionValue: String = "",

    val time: LocalDateTime = LocalDateTime.now()
) {

    fun typeMarkdownString(){

    }
}