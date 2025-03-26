package dev.notypie.application.service.relay.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class Envelope(
    val schema: Schema,
    val payload: Payload
):MessageProcessorParameter()

data class Schema(
    val type: String,
    val fields: List<Field> = emptyList(),
    val optional: Boolean,
    val name: String,
    val version: Int
)

data class Field(
    val type: String,
    val fields: List<SubField> = emptyList(),
    val optional: Boolean,
    val name: String? = null,
    val version: Int? = null,
    val parameters: Parameters? = null,
    @field:JsonProperty("default")
    val defaultValue: String? = null,
    val field: String
)

data class SubField(
    val type: String,
    val optional: Boolean,
    val field: String,
    val name: String? = null,
    val version: Int? = null,
    val parameters: Parameters? = null,
    @field:JsonProperty("default")
    val defaultValue: String? = null
)


data class Parameters(
    val allowed: String
)


data class Payload(
    val before: Map<String, Any>? = null,
    val after: Map<String, Any>? = null,
    val source: Source,
    val transaction: Transaction? = null,
    val op: String,
    @field:JsonProperty("ts_ms")
    val timeMillisecond: Long,
    @field:JsonProperty("ts_us")
    val timeMicrosecond: Long,
    @field:JsonProperty("ts_ns")
    val timeNanosecond: Long
)


data class Source(
    val version: String,
    val connector: String,
    val name: String,
    val snapshot: String? = null,
    val db: String,
    val table: String,
    val sequence: String? = null,
    @field:JsonProperty("ts_ms")
    val timeMillisecond: Long,
    @field:JsonProperty("ts_us")
    val timeMicrosecond: Long,
    @field:JsonProperty("ts_ns")
    val timeNanosecond: Long,
    @field:JsonProperty("server_id") val serverId: Long,
    val gtid: String? = null,
    val file: String,
    val pos: Int,
    val row: Int,
    val thread: String? = null,
    val query: String? = null
)


data class Transaction(
    val id: String,
    @field:JsonProperty("total_order")
    val totalOrder: Long,
    @field:JsonProperty("data_collection_order")
    val dataCollectionOrder: Long
)