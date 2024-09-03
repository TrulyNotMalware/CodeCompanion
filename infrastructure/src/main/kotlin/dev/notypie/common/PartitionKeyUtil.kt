package dev.notypie.common

import kotlin.math.abs

class PartitionKeyUtil private constructor(){
    companion object {
        private const val PARTITION_KEY_COUNT = 6

        fun createPartitionKey(number: Int): Int = abs(number) % PARTITION_KEY_COUNT
        fun createPartitionKey(string: String): Int = abs(string.hashCode()) % PARTITION_KEY_COUNT
    }
}