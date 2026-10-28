package com.qiye.ledgerflow.core

import com.qiyue.cashflow.shared.CivilDate

object LedgerCalendar {
    val firstDay = CivilDate(1900, 1, 1).toEpochDay()
    val lastDay = CivilDate(2199, 12, 31).toEpochDay()

    fun validateDay(day: Long) { require(day in firstDay..lastDay) { "日期须在 1900 至 2199 年之间" } }

    fun parse(text: String): Long {
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(text)) { "日期格式为 YYYY-MM-DD" }
        val parts = text.split('-').map { it.toInt() }
        val date = CivilDate(parts[0], parts[1], parts[2])
        require(date.year in 1900..2199 && date.month in 1..12 && date.day in 1..date.lengthOfMonth()) { "日期不存在" }
        return date.toEpochDay()
    }

    fun format(day: Long): String {
        val date = CivilDate.fromEpochDay(day)
        return date.year.toString().padStart(4, '0') + "-" + date.month.toString().padStart(2, '0') + "-" + date.day.toString().padStart(2, '0')
    }

    /** Period is named by its starting month. Day 31 clamps independently in each month. */
    fun period(year: Int, month: Int, startDay: Int): LongRange {
        require(startDay in 1..31)
        val base = CivilDate(year, month, 1)
        val next = base.plusMonths(1)
        val start = base.copy(day = minOf(startDay, base.lengthOfMonth())).toEpochDay()
        val endExclusive = next.copy(day = minOf(startDay, next.lengthOfMonth())).toEpochDay()
        return start until endExclusive
    }

    fun periodMonth(today: Long, startDay: Int): CivilDate {
        val date = CivilDate.fromEpochDay(today)
        val start = minOf(startDay, date.lengthOfMonth())
        return if (date.day < start) date.copy(day = 1).plusMonths(-1) else date.copy(day = 1)
    }
}
