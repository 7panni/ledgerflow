package com.qiye.ledgerflow.cashflow.data

import java.time.LocalDate
import com.qiye.ledgerflow.core.LedgerCalendar

object BudgetCycles {
    fun containing(date:LocalDate,startDay:Int):Pair<LocalDate,LocalDate>{
        val month=LedgerCalendar.periodMonth(date.toEpochDay(),startDay)
        val range=LedgerCalendar.period(month.year,month.month,startDay)
        return LocalDate.ofEpochDay(range.first) to LocalDate.ofEpochDay(range.last)
    }
}
