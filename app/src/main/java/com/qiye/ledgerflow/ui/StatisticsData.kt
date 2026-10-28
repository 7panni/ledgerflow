package com.qiye.ledgerflow.ui

import com.qiye.ledgerflow.core.*
import com.qiyue.cashflow.shared.CivilDate

enum class StatisticsPeriod(val months:Int,val label:String){MONTH(1,"月"),QUARTER(3,"季度"),YEAR(12,"年度")}
data class StatisticsWindow(val label:String,val range:LongRange,val canPrevious:Boolean,val canNext:Boolean)
data class StatisticsRow(val category:String,val subcategory:String,val amountMinor:Long,val entries:List<Entry>)
object StatisticsData {
    fun window(today:Long,startDay:Int,period:StatisticsPeriod,offset:Int):StatisticsWindow {
        val month=LedgerCalendar.periodMonth(today,startDay)
        val baseMonth=when(period){StatisticsPeriod.MONTH->month.month;StatisticsPeriod.QUARTER->(month.month-1)/3*3+1;StatisticsPeriod.YEAR->1}
        val origin=month.year*12+baseMonth-1
        val firstAllowed=1900*12;val lastAllowed=2199*12+12-period.months
        val index=(origin.toLong()+offset.toLong()*period.months).coerceIn(firstAllowed.toLong(),lastAllowed.toLong()).toInt()
        val first=CivilDate(index/12,index%12+1,1);val last=first.plusMonths(period.months-1)
        val range=LedgerCalendar.period(first.year,first.month,startDay).first..minOf(LedgerCalendar.lastDay,LedgerCalendar.period(last.year,last.month,startDay).last)
        val label=when(period){StatisticsPeriod.MONTH->"${first.year}年${first.month}月";StatisticsPeriod.QUARTER->"${first.year}年第${(first.month-1)/3+1}季度";StatisticsPeriod.YEAR->"${first.year}年"}
        return StatisticsWindow(label,range,index>firstAllowed,index<lastAllowed)
    }
    fun entries(state:LedgerState,range:LongRange)=state.entries.filter{it.epochDay in range&&!it.excludeFromStatistics&&it.currencyCode=="CNY"&&it.kind.isOrdinary()}
    fun rows(entries:List<Entry>,kind:EntryKind):List<StatisticsRow> {
        require(kind==EntryKind.EXPENSE||kind==EntryKind.INCOME)
        return entries.filter{if(kind==EntryKind.INCOME)it.kind==EntryKind.INCOME else it.kind==EntryKind.EXPENSE||it.kind==EntryKind.REFUND}
            .groupBy{it.category to it.subcategory}.map{(name,group)->StatisticsRow(name.first,name.second,group.fold(0L){n,e->Money.add(n,if(e.kind==EntryKind.REFUND)-e.amountMinor else e.amountMinor)},group)}
            .sortedWith(compareByDescending<StatisticsRow>{it.amountMinor}.thenBy{it.category}.thenBy{it.subcategory})
    }
}
