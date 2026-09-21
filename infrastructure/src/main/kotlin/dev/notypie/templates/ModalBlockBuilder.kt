package dev.notypie.templates

import com.slack.api.model.block.*
import com.slack.api.model.block.Blocks.*
import dev.notypie.domain.command.dto.modals.*
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.impl.command.slack.States
import dev.notypie.templates.dto.CheckBoxOptions
import dev.notypie.templates.dto.InteractionLayoutBlock
import dev.notypie.templates.dto.InteractiveObject
import java.util.UUID

class ModalBlockBuilder(
    private val modalElementBuilder: ModalElementBuilder = ModalElementBuilder(),
) {
    companion object {
        const val DEFAULT_CALENDAR_IMAGE_URLS = "https://api.slack.com/img/blocks/bkb_template_images/notifications.png"
    }

    fun headerBlock(text: String): HeaderBlock =
        header {
            it.text(modalElementBuilder.plainTextObject(text = text))
        }

    fun timeScheduleBlock(timeScheduleInfo: TimeScheduleInfo, isMarkDown: Boolean = false): SectionBlock =
        section {
            if (isMarkDown) {
                it.text(modalElementBuilder.markdownTextObject(markdownText = timeScheduleInfo.toString()))
            } else {
                it.text(modalElementBuilder.plainTextObject(text = timeScheduleInfo.toString()))
            }
            it.accessory(
                modalElementBuilder.imageBlockElement(
                    imageUrl = DEFAULT_CALENDAR_IMAGE_URLS,
                    altText = "calendar thumbnail",
                ),
            )
        }

    fun approvalBlock(approvalContents: ApprovalContents): InteractionLayoutBlock {
        val interactionPayload = "${approvalContents.idempotencyKey}, ${approvalContents.commandDetailType.name}"
        val approvalButton: InteractiveObject =
            modalElementBuilder.approvalButtonElement(
                approvalButtonName = approvalContents.approvalButtonName,
                interactionPayload = interactionPayload,
            )
        val rejectButton: InteractiveObject =
            modalElementBuilder.rejectButtonElement(
                rejectButtonName = approvalContents.rejectButtonName,
                interactionPayload = interactionPayload,
            )

        val layout =
            actions {
                it.elements(
                    listOf(
                        approvalButton.element,
                        rejectButton.element,
                    ),
                )
            }
        return toInteractionLayout(approvalButton.state, rejectButton.state, layout = layout)
    }

    fun cancelMeetingActionsBlock(meetingUid: UUID, listIdempotencyKey: UUID): InteractionLayoutBlock {
        val routingValue = "$listIdempotencyKey,${CommandDetailType.CANCEL_MEETING.name},$meetingUid"
        val cancelButton: InteractiveObject =
            modalElementBuilder.cancelMeetingButtonElement(
                buttonName = "Cancel",
                interactionPayload = routingValue,
            )
        val layout =
            actions {
                it.blockId(MeetingActionIds.CANCEL_BLOCK_ID)
                it.elements(listOf(cancelButton.element))
            }
        return toInteractionLayout(cancelButton.state, layout = layout)
    }

    fun hostMeetingActionsBlock(meetingUid: UUID, listIdempotencyKey: UUID): InteractionLayoutBlock {
        fun routingValue(detailType: CommandDetailType) = "$listIdempotencyKey,${detailType.name},$meetingUid"
        val rescheduleRoutingValue = routingValue(CommandDetailType.MEETING_RESCHEDULE_REQUEST)
        val addParticipantRoutingValue = routingValue(CommandDetailType.MEETING_ADD_PARTICIPANT_REQUEST)
        val cancelRoutingValue = routingValue(CommandDetailType.CANCEL_MEETING)
        // block_id/action_id must be unique per row; Slack rejects the whole message (invalid_blocks) on collision.
        val rescheduleButton: InteractiveObject =
            modalElementBuilder.rescheduleMeetingButtonElement(
                buttonName = "Reschedule",
                interactionPayload = rescheduleRoutingValue,
                actionId = "${MeetingActionIds.RESCHEDULE_ACTION_ID}_$meetingUid",
            )
        val addParticipantButton: InteractiveObject =
            modalElementBuilder.addParticipantButtonElement(
                buttonName = "Add participant",
                interactionPayload = addParticipantRoutingValue,
                actionId = "${MeetingActionIds.ADD_PARTICIPANT_ACTION_ID}_$meetingUid",
            )
        val cancelButton: InteractiveObject =
            modalElementBuilder.cancelMeetingButtonElement(
                buttonName = "Cancel",
                interactionPayload = cancelRoutingValue,
                actionId = "${MeetingActionIds.CANCEL_ACTION_ID}_$meetingUid",
            )
        val layout =
            actions {
                it.blockId("${MeetingActionIds.CANCEL_BLOCK_ID}_$meetingUid")
                it.elements(listOf(rescheduleButton.element, addParticipantButton.element, cancelButton.element))
            }
        return toInteractionLayout(
            rescheduleButton.state,
            addParticipantButton.state,
            cancelButton.state,
            layout = layout,
        )
    }

    fun simpleText(text: String, isMarkDown: Boolean = false): SectionBlock =
        section {
            if (isMarkDown) {
                it.text(modalElementBuilder.markdownTextObject(markdownText = text))
            } else {
                it.text(modalElementBuilder.plainTextObject(text = text))
            }
        }

    fun textBlock(vararg texts: String, isMarkDown: Boolean = false): SectionBlock =
        section {
            it.fields(
                texts.map { text ->
                    if (isMarkDown) {
                        modalElementBuilder.markdownTextObject(markdownText = text)
                    } else {
                        modalElementBuilder.plainTextObject(text = text)
                    }
                },
            )
        }

    fun dividerBlock(): DividerBlock = divider()

    fun selectionBlock(selectionContents: SelectionContents): InteractionLayoutBlock {
        val multiSelection =
            modalElementBuilder.selectionElement(
                placeholderText = selectionContents.placeholderText,
                contents = selectionContents.contents,
            )
        val layout =
            section {
                it.text(
                    modalElementBuilder.markdownTextObject(
                        markdownText = "*${selectionContents.title}*\n${selectionContents.explanation}",
                    ),
                )
                it.accessory(multiSelection.element)
            }
        return toInteractionLayout(multiSelection.state, layout = layout)
    }

    fun multiUserSelectBlock(contents: MultiUserSelectContents): InteractionLayoutBlock {
        val multiUserSelection = modalElementBuilder.multiUserSelectionElement(contents = contents)
        val layout =
            input {
                it.label(modalElementBuilder.plainTextObject(text = contents.title))
                it.element(multiUserSelection.element)
            }
        return toInteractionLayout(multiUserSelection.state, layout = layout)
    }

    fun plainTextInputBlock(contents: TextInputContents): InputBlock =
        input {
            it.label(modalElementBuilder.plainTextObject(text = contents.title))
            it.element(modalElementBuilder.plainTextInputElement(contents = contents))
        }

    fun calendarThumbnailBlock(title: String, markdownBody: String): SectionBlock =
        section {
            it.text(modalElementBuilder.markdownTextObject(markdownText = "*$title*\n$markdownBody"))
            it.accessory(
                modalElementBuilder.imageBlockElement(
                    imageUrl = DEFAULT_CALENDAR_IMAGE_URLS,
                    altText = "calendar thumbnail",
                ),
            )
        }

    fun userNameWithThumbnailBlock(userName: String, userThumbnailUrl: String, mkdIntroduceComment: String = "") =
        context {
            it.elements(
                listOf(
                    modalElementBuilder.markdownTextObject(markdownText = mkdIntroduceComment),
                    modalElementBuilder.markdownTextObject(markdownText = "*$userName* "),
                    modalElementBuilder.imageBlockElement(
                        imageUrl = userThumbnailUrl,
                        altText = "thumbnail",
                    ),
                ),
            )
        }

    fun selectDateTimeScheduleBlock(): InteractionLayoutBlock {
        val datePickerElement = modalElementBuilder.datePickerElement()
        val startTimePickerElement =
            modalElementBuilder.timePickerElement(placeholderText = "Start time")
        val endTimePickerElement =
            modalElementBuilder.timePickerElement(
                placeholderText = "End time (optional)",
                initialTime = null,
            )
        val layout =
            actions {
                it.elements(
                    listOf(
                        datePickerElement.element,
                        startTimePickerElement.element,
                        endTimePickerElement.element,
                    ),
                )
            }
        return toInteractionLayout(
            datePickerElement.state,
            startTimePickerElement.state,
            endTimePickerElement.state,
            layout = layout,
        )
    }

    fun checkBoxesBlock(vararg options: CheckBoxOptions, isMarkDown: Boolean = true): InteractionLayoutBlock {
        val checkboxElements = modalElementBuilder.checkboxElements(options = options, isMarkDown = isMarkDown)
        val layout =
            actions {
                it.elements(listOf(checkboxElements.element))
            }
        return toInteractionLayout(
            checkboxElements.state,
            layout = layout,
        )
    }

    fun radioButtonBlock(
        vararg options: String,
        description: String,
        isMarkDown: Boolean = true,
    ): InteractionLayoutBlock {
        val radioButtonElements =
            modalElementBuilder.radioButtonElements(
                options = toCheckBoxOptions(stringOption = options),
                description = description,
            )
        val layout =
            section {
                it.text(modalElementBuilder.textObject(text = description, isMarkDown = isMarkDown))
                it.accessory(radioButtonElements.element)
            }
        return toInteractionLayout(
            radioButtonElements.state,
            layout = layout,
        )
    }

    private fun toCheckBoxOptions(vararg stringOption: String) =
        stringOption
            .map {
                CheckBoxOptions(text = it)
            }.toTypedArray()

    private fun toInteractionLayout(vararg states: States, layout: LayoutBlock): InteractionLayoutBlock =
        InteractionLayoutBlock(
            layout = layout,
            interactiveObjects = listOf(*states),
        )
}
