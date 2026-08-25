package dev.notypie.domain.command.inbound

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class InboundFormTest :
    BehaviorSpec({

        given("a form with two TEXT fields (title then reason)") {
            val form =
                InboundForm(
                    fields =
                        listOf(
                            InboundField(
                                key = null,
                                kind = InboundFieldKind.TEXT,
                                isSelected = true,
                                rawValue = "title",
                            ),
                            InboundField(
                                key = null,
                                kind = InboundFieldKind.TEXT,
                                isSelected = true,
                                rawValue = "reason",
                            ),
                        ),
                )

            then("all(TEXT) preserves positional order: [0]=title, [1]=reason") {
                val texts = form.all(kind = InboundFieldKind.TEXT)
                texts[0].rawValue shouldBe "title"
                texts[1].rawValue shouldBe "reason"
            }

            then("firstValue(TEXT) returns the first field's value") {
                form.firstValue(kind = InboundFieldKind.TEXT) shouldBe "title"
            }
        }

        given("a form with two TIME fields (start then end)") {
            val form =
                InboundForm(
                    fields =
                        listOf(
                            InboundField(
                                key = null,
                                kind = InboundFieldKind.TIME,
                                isSelected = true,
                                rawValue = "09:00",
                            ),
                            InboundField(
                                key = null,
                                kind = InboundFieldKind.TIME,
                                isSelected = true,
                                rawValue = "10:00",
                            ),
                        ),
                )

            then("all(TIME) preserves positional order: [0]=start, [1]=end") {
                val times = form.all(kind = InboundFieldKind.TIME)
                times[0].rawValue shouldBe "09:00"
                times[1].rawValue shouldBe "10:00"
            }
        }

        given("a form whose standup TEXT fields arrive out of block-id order") {
            val form =
                InboundForm(
                    fields =
                        listOf(
                            InboundField(
                                key = "standup_q_1",
                                kind = InboundFieldKind.TEXT,
                                isSelected = true,
                                rawValue = "Work on #13",
                            ),
                            InboundField(
                                key = "standup_q_0",
                                kind = InboundFieldKind.TEXT,
                                isSelected = true,
                                rawValue = "Finished #12",
                            ),
                        ),
                )

            then("all(TEXT) preserves inbound order (unsorted)") {
                form.all(kind = InboundFieldKind.TEXT).map { it.key } shouldBe listOf("standup_q_1", "standup_q_0")
            }

            then("sorting by the standup_q_<index> block id realigns responses") {
                val sorted =
                    form
                        .all(kind = InboundFieldKind.TEXT)
                        .sortedBy { it.key?.removePrefix("standup_q_")?.toIntOrNull() ?: Int.MAX_VALUE }
                        .map { it.rawValue }
                sorted shouldBe listOf("Finished #12", "Work on #13")
            }
        }

        given("a form keyed by block id") {
            val form =
                InboundForm(
                    fields =
                        listOf(
                            InboundField(
                                key = "block_a",
                                kind = InboundFieldKind.CHOICE,
                                isSelected = true,
                                rawValue = "chosen",
                            ),
                        ),
                )

            then("value(key) returns the raw value, missing keys yield empty string") {
                form.value(key = "block_a") shouldBe "chosen"
                form.value(key = "missing") shouldBe ""
            }

            then("isSelected(key) reflects the field, missing keys yield false") {
                form.isSelected(key = "block_a") shouldBe true
                form.isSelected(key = "missing") shouldBe false
            }
        }
    })
