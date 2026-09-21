package dev.notypie.domain.command

import dev.notypie.domain.command.intent.DefaultIntentQueue
import dev.notypie.domain.command.intent.IntentQueue

fun createIntentQueue(): IntentQueue = DefaultIntentQueue()
