package com.foglio.wlock

data class EventData(
    val name: String,
    val time: Int,
    val showAt: Int,
    val showOnWorkDays: Boolean,
    val showOnWeekEnds: Boolean
)
