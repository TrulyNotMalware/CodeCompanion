package dev.notypie.slack

import com.slack.api.model.block.LayoutBlock
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo

interface SlackTemplateBuilder {
    fun simpleTextResponseTemplate( headLineText: String, body: String, isMarkDown: Boolean): List<LayoutBlock>
    fun simpleScheduleNoticeTemplate( headLineText: String, timeScheduleInfo: TimeScheduleInfo): List<LayoutBlock>
}