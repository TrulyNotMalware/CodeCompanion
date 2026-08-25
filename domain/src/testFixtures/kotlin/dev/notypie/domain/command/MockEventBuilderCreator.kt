package dev.notypie.domain.command

import dev.notypie.domain.command.intent.DefaultIntentQueue
import dev.notypie.domain.command.intent.IntentQueue

/**
 * Creates a [DefaultIntentQueue] for verifying intents produced by context execution.
 */
fun createIntentQueue(): IntentQueue = DefaultIntentQueue()
