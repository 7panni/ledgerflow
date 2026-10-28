package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.ui.*
import org.junit.Test
import org.junit.Assert.*

class StatisticsViewTest {
    @Test fun monthQuarterAndYearShareConfiguredStartingDay(){
        val today=LedgerCalendar.parse("2026-10-22")
        fun dates(p:StatisticsPeriod,offset:Int=0)=StatisticsData.window(today,25,p,offset).range.let{LedgerCalendar.format(it.first) to LedgerCalendar.format(it.last)}
        assertEquals("2026-09-25" to "2026-10-24",dates(StatisticsPeriod.MONTH))
        assertEquals("2026-07-25" to "2026-10-24",dates(StatisticsPeriod.QUARTER))
        assertEquals("2026-01-25" to "2027-01-24",dates(StatisticsPeriod.YEAR))
        assertEquals("2026-10-25" to "2027-01-24",dates(StatisticsPeriod.QUARTER,1))
        assertEquals("2025-01-25" to "2026-01-24",dates(StatisticsPeriod.YEAR,-1))
    }
    @Test fun shortMonthsLeapYearsAndRangeLimits(){
        val leap=StatisticsData.window(LedgerCalendar.parse("2024-03-01"),31,StatisticsPeriod.MONTH,0)
        assertEquals("2024-02-29",LedgerCalendar.format(leap.range.first));assertEquals("2024-03-30",LedgerCalendar.format(leap.range.last))
        val start=StatisticsData.window(LedgerCalendar.firstDay,1,StatisticsPeriod.QUARTER,-10000)
        assertFalse(start.canPrevious);assertEquals(LedgerCalendar.firstDay,start.range.first)
        val end=StatisticsData.window(LedgerCalendar.lastDay,31,StatisticsPeriod.YEAR,10000)
        assertFalse(end.canNext);assertTrue(end.range.last<=LedgerCalendar.lastDay)
    }
    @Test fun classificationMatchesSummaryAndPreservesRefundDrill(){
        val day=LedgerCalendar.parse("2026-10-04")
        fun e(seq:Long,kind:EntryKind,amount:Long,category:String="自制餐饮",sub:String="早餐")=Entry("entry-$seq",seq,kind,amount,day,600,category,subcategory=sub,affectsBalance=false,affectsBudget=false)
        val entries=listOf(e(1,EntryKind.EXPENSE,10000),e(2,EntryKind.REFUND,2000),e(3,EntryKind.INCOME,3000),e(4,EntryKind.TRANSFER,900),e(5,EntryKind.REPAY,2000),e(6,EntryKind.EXPENSE,300).copy(excludeFromStatistics=true),e(7,EntryKind.EXPENSE,100).copy(currencyCode="USD"),e(8,EntryKind.EXPENSE,400,"自制交通",""))
        val state=LedgerState(entries=entries);val selected=StatisticsData.entries(state,day..day)
        val expense=StatisticsData.rows(selected,EntryKind.EXPENSE);val income=StatisticsData.rows(selected,EntryKind.INCOME)
        assertEquals(listOf(8000L,400L),expense.map{it.amountMinor});assertEquals(2,expense.first().entries.size)
        assertEquals(8400L,expense.sumOf{it.amountMinor});assertEquals(3000L,income.sumOf{it.amountMinor})
        assertEquals(LedgerEngine.periodSummary(state,day..day).expenseMinor,expense.sumOf{it.amountMinor})
        assertEquals(2,StatisticsData.rows(listOf(e(9,EntryKind.REFUND,3000),e(10,EntryKind.EXPENSE,1000)),EntryKind.EXPENSE).single().entries.size)
        assertTrue(StatisticsData.rows(emptyList(),EntryKind.EXPENSE).isEmpty())
    }
}
