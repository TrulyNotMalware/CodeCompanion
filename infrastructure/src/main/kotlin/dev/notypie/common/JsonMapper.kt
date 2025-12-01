package dev.notypie.common

import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinFeature
import tools.jackson.module.kotlin.KotlinModule

val jsonMapper: JsonMapper =
    JsonMapper
        .builder()
        .findAndAddModules()
        .addModule(
            KotlinModule
                .Builder()
                .enable(KotlinFeature.UseJavaDurationConversion)
                .enable(KotlinFeature.KotlinPropertyNameAsImplicitName)
                .build(),
        ).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build()
