package dev.notypie.domain.command.dto.modals

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.TimeZone

data class TimeScheduleInfo(
    val scheduleName: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,

    val timeZone: TimeZone = TimeZone.getTimeZone(DEFAULT_TIME_ZONE),
    val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
){
    companion object{
        const val DEFAULT_TIME_ZONE = "Asia/Seoul"
    }

    override fun toString(): String =
        "${this.scheduleName}\n${this.startTime.format(this.timeFormatter)} ~ ${this.endTime.format(this.timeFormatter)}"
}