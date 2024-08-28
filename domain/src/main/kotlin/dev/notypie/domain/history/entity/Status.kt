package dev.notypie.domain.history.entity

enum class Status {
    IN_PROGRESSED,
    SUCCESS,
    FAILED;

    companion object {
        fun isOk(status: Status) =
            when (status) {
                IN_PROGRESSED -> true
                SUCCESS -> true
                FAILED -> false
            }
    }
}