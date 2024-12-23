package dev.notypie.common

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

val objectMapper: ObjectMapper = ObjectMapper()
    .registerKotlinModule()
    .registerModules(Jdk8Module(), JavaTimeModule())
    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)