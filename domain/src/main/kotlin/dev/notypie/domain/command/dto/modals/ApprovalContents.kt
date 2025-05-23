package dev.notypie.domain.command.dto.modals

import dev.notypie.domain.command.entity.CommandDetailType
import java.time.LocalDateTime
import java.util.UUID

data class ApprovalContents(
    val headLineText: String = "Approval Requests",
    val type: ApprovalContentType = ApprovalContentType.SIMPLE_REQUEST_FORM,
    val reason: String,
    val subTitle: String = "",
    val publisherId: String,

    val approvalButtonName: String = "Approval",
    val rejectButtonName: String = "Deny",
    val idempotencyKey: UUID,
    val commandDetailType: CommandDetailType,

    val time: LocalDateTime = LocalDateTime.now()
){
    val interactionValue: String = "$idempotencyKey, $commandDetailType"
}

enum class ApprovalContentType{
    SIMPLE_REQUEST_FORM,
    FINAL_CONFIRM_FORM,

}