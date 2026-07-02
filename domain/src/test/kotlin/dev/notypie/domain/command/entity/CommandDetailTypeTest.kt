package dev.notypie.domain.command.entity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the wire contract of [CommandDetailType]. The Kotlin identifiers are free to be renamed to
 * transport-neutral domain vocabulary, but [CommandDetailType.wireValue] MUST keep emitting the
 * exact legacy token, because those strings are already persisted in the outbox column and embedded
 * in live Slack modal `private_metadata` / button values that users can still trigger. If a rename
 * ever changes a wireValue, this test fails and the round-trip with in-flight data breaks.
 */
class CommandDetailTypeTest :
    StringSpec({
        // identifier -> the legacy serialized token it must forever produce
        val legacyWireValues =
            mapOf(
                CommandDetailType.NOTHING to "NOTHING",
                CommandDetailType.SIMPLE_TEXT to "SIMPLE_TEXT",
                CommandDetailType.REPLACE_TEXT to "REPLACE_TEXT",
                CommandDetailType.ERROR_RESPONSE to "ERROR_RESPONSE",
                CommandDetailType.APPROVAL_REQUEST to "APPROVAL_FORM",
                CommandDetailType.APPLY_REQUEST to "REQUEST_APPLY_FORM",
                CommandDetailType.MEETING_CREATE_REQUEST to "REQUEST_MEETING_FORM",
                CommandDetailType.GET_MEETING_LIST to "GET_MEETING_LIST",
                CommandDetailType.MEETING_APPROVAL_REQUEST to "MEETING_APPROVAL_NOTICE_FORM",
                CommandDetailType.MEETING_DECLINE_REASON to "DECLINE_REASON_MODAL",
                CommandDetailType.CANCEL_MEETING to "CANCEL_MEETING",
                CommandDetailType.MEETING_RESCHEDULE_REQUEST to "RESCHEDULE_MEETING",
                CommandDetailType.MEETING_RESCHEDULE_SUBMIT to "RESCHEDULE_MEETING_SUBMIT",
                CommandDetailType.MEETING_ADD_PARTICIPANT_REQUEST to "ADD_PARTICIPANT",
                CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT to "ADD_PARTICIPANT_SUBMIT",
                CommandDetailType.MEETING_REMINDER to "MEETING_REMINDER",
                CommandDetailType.DAILY_AGENDA to "DAILY_AGENDA",
                CommandDetailType.STATUS_REPORT to "STATUS_REPORT",
                CommandDetailType.STANDUP_PROMPT to "STANDUP_FILL",
                CommandDetailType.STANDUP_ANSWER_SUBMIT to "STANDUP_ANSWER_SUBMIT",
                CommandDetailType.STANDUP_SETUP_REQUEST to "STANDUP_SETUP_FORM",
                CommandDetailType.STANDUP_SETUP_SUBMIT to "STANDUP_SETUP_SUBMIT",
                CommandDetailType.STANDUP_SUMMARY to "STANDUP_SUMMARY",
                CommandDetailType.APPROVAL_CALLBACK to "NOTICE_FORM",
            )

        "every value keeps its legacy wireValue and the pin covers all values" {
            legacyWireValues.keys.toSet() shouldBe CommandDetailType.entries.toSet()
            legacyWireValues.forEach { (value, legacyToken) -> value.wireValue shouldBe legacyToken }
        }

        "fromWireValue round-trips every value" {
            CommandDetailType.entries.forEach { value ->
                CommandDetailType.fromWireValue(value.wireValue) shouldBe value
            }
        }

        "fromWireValue degrades unknown tokens to NOTHING (tolerant, inbound Slack)" {
            CommandDetailType.fromWireValue("SOME_UNKNOWN_TOKEN") shouldBe CommandDetailType.NOTHING
            CommandDetailType.fromWireValue("") shouldBe CommandDetailType.NOTHING
        }

        "requireWireValue throws on unknown tokens (strict, trusted outbox data)" {
            shouldThrow<IllegalArgumentException> { CommandDetailType.requireWireValue("SOME_UNKNOWN_TOKEN") }
            CommandDetailType.entries.forEach { value ->
                CommandDetailType.requireWireValue(value.wireValue) shouldBe value
            }
        }
    })
