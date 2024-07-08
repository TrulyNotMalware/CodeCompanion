package dev.notypie.slack

import com.slack.api.model.block.LayoutBlock

interface SlackTemplateBuilder {
    fun simpleTextResponseTemplate( headLineText: String, body: String, isMarkDown: Boolean): List<LayoutBlock>
}