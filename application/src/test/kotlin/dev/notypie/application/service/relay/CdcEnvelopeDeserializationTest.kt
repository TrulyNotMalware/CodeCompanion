package dev.notypie.application.service.relay

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer

class CdcEnvelopeDeserializationTest :
    BehaviorSpec({
        fun listenerDeserializer(): JacksonJsonDeserializer<Any> =
            JacksonJsonDeserializer<Any>().apply {
                configure(
                    mapOf(
                        "spring.json.use.type.headers" to "false",
                        "spring.json.value.default.type" to Envelope::class.java.name,
                        "spring.json.trusted.packages" to "*",
                    ),
                    false,
                )
            }

        given("a Debezium record whose metadata differs from the connector the model was written against") {
            val json =
                """
                {
                  "schema": {"type": "struct", "optional": false, "name": "cdc.code_companion.outbox_message.Envelope"},
                  "payload": {
                    "before": null,
                    "after": {"event_id": "evt-1", "status": "PENDING", "created_at": 1777000000000000},
                    "source": {"version": "1.9.7.Final", "connector": "mysql", "name": "cdc", "ts_ms": 1, "db": "code_companion", "table": "outbox_message", "server_id": 1, "file": "binlog.000001", "pos": 4, "row": 0, "new_field": true},
                    "op": "c",
                    "ts_ms": 1,
                    "transaction": null
                  }
                }
                """.trimIndent()

            `when`("the listener's deserializer reads it") {
                val result =
                    runCatching {
                        listenerDeserializer().deserialize(
                            "cdc.code_companion.outbox_message",
                            json.toByteArray(),
                        )
                    }

                then("the after-image and op come through; missing or extra metadata does not matter") {
                    val envelope = result.getOrThrow().shouldBeInstanceOf<Envelope>()
                    envelope.payload.op shouldBe "c"
                    envelope.payload.after?.get("event_id") shouldBe "evt-1"
                }
            }
        }
    })
