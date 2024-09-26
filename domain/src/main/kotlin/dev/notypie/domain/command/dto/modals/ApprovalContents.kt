package dev.notypie.domain.command.dto.modals

import java.time.LocalDateTime

data class ApprovalContents(
    val type: ApprovalContentType = ApprovalContentType.SIMPLE_REQUEST_FORM,
    val reason: String,

    val approvalButtonName: String = "Approval",
    val approvalInteractionValue: String,

    val rejectButtonName: String = "Deny",
    val rejectInteractionValue: String,

    val time: LocalDateTime = LocalDateTime.now()
)

enum class ApprovalContentType{
    SIMPLE_REQUEST_FORM,
    FINAL_CONFIRM_FORM,

}