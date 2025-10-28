package dev.notypie.domain.history.entity

enum class Status {
    IN_PROGRESSED,
    SUCCESS,
    FAILED,
    DO_NOTHING,
    ;

    companion object {
        fun isOk(status: Status) =
            when (status) {
                IN_PROGRESSED -> true
                SUCCESS -> true
                DO_NOTHING -> false
                FAILED -> false
            }
    }
}
