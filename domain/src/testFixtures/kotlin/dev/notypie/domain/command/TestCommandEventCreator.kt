package dev.notypie.domain.command

import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import java.util.UUID


data class TestPayload(val value: String, override val eventId: UUID) : EventPayload
data class TestCommandEvent(
    override val idempotencyKey: UUID = UUID.randomUUID(),
    override val payload: TestPayload = TestPayload("test", UUID.randomUUID()),
    override val destination: String = "",
    override val isInternal: Boolean = true,
    override val timestamp: Long = System.currentTimeMillis(),
    val name: String
): CommandEvent<TestPayload>

const val INTERNAL_EVENT_NAME="I_AM_INTERNAL_EVENT"
const val EXTERNAL_EVENT_NAME="I_AM_EXTERNAL_EVENT"

fun createInternalTestEvent(name: String = INTERNAL_EVENT_NAME) =
    TestCommandEvent(name = name)
fun createExternalTestEvent(name: String = EXTERNAL_EVENT_NAME) =
    TestCommandEvent(isInternal = false, name = EXTERNAL_EVENT_NAME)

fun createDomainEventQueue(): EventQueue<TestCommandEvent> = DefaultEventQueue()