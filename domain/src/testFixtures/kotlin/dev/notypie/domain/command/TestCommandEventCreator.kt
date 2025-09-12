package dev.notypie.domain.command

import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import java.util.UUID

data class TestPayload(
    val value: String,
    override val eventId: UUID,
) : EventPayload

data class TestCommandEvent(
    override val idempotencyKey: UUID = UUID.randomUUID(),
    override val payload: TestPayload = TestPayload("test", UUID.randomUUID()),
    override val destination: String = "",
    override val isInternal: Boolean = true,
    override val timestamp: Long = System.currentTimeMillis(),
    override val name: String,
    override val type: CommandDetailType = CommandDetailType.NOTHING,
) : CommandEvent<TestPayload>

const val INTERNAL_EVENT_NAME = "I_AM_INTERNAL_EVENT"
const val EXTERNAL_EVENT_NAME = "I_AM_EXTERNAL_EVENT"

fun createInternalTestEvent(name: String = INTERNAL_EVENT_NAME) = TestCommandEvent(name = name)

fun createExternalTestEvent(name: String = EXTERNAL_EVENT_NAME) = TestCommandEvent(isInternal = false, name = name)

fun createDomainEventQueue(): EventQueue<CommandEvent<EventPayload>> = DefaultEventQueue()

fun EventQueue<CommandEvent<EventPayload>>.flushQueue() {
    while (poll() != null) {
        // keep polling until queue is empty
    }
}
