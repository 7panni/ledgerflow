package com.qiye.ledgerflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.LedgerFlowTiming
import com.qiye.ledgerflow.cashflow.ui.IntegratedCashflowHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

private data class HomeActual(val source:LedgerState,val range:LongRange,val summary:Result<PeriodSummary>,val entries:List<Entry>)

@Composable fun HomeScreen(state:LedgerState,padding:PaddingValues,vm:LedgerViewModel,onCorrection:()->Unit,onPlans:()->Unit,onEntry:(Entry)->Unit,onNewEntry:()->Unit,onAllEntries:()->Unit,onDelete:(Entry)->Unit,onRefund:(Entry)->Unit){
    val today=LocalClock.today();val initial=LedgerCalendar.periodMonth(today,state.settings.monthStartDay)
    var offset by rememberSaveable(state.settings.monthStartDay){mutableIntStateOf(0)}
    val month=initial.plusMonths(offset);val range=LedgerCalendar.period(month.year,month.month,state.settings.monthStartDay)
    val actual by produceState<HomeActual?>(null,state,range){
        val result=withContext(Dispatchers.Default){LedgerFlowTiming.measure("home_actual",state.entries.size.toLong()){HomeActual(state,range,runCatching{LedgerEngine.periodSummary(state,range)},state.entries.filter{it.epochDay in range}.sortedWith(compareByDescending<Entry>{it.epochDay}.thenByDescending{it.minuteOfDay}.thenByDescending{it.second}.thenByDescending{it.sequence}))}}
        ensureActive();value=result;LedgerFlowTiming.mark("home_actual_ready")
    }
    val current=actual?.takeIf{it.source===state&&it.range==range}
    val summary=current?.summary
    val entries=current?.entries
    IntegratedCashflowHome(state,padding,vm,onCorrection,onPlans,header={
        Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
            PageSectionHeader("本期收支（人民币）") {Row(verticalAlignment=Alignment.CenterVertically){TextButton({offset--},enabled=month.year>1900){Text("‹")};Text("${month.year}年${month.month}月",fontSize=10.sp,color=Muted);TextButton({offset++},enabled=month.year<2199){Text("›")}}}
            Column(Modifier.fillMaxWidth().background(Panel).border(1.dp,Line).padding(12.dp)){
                if(summary==null)CircularProgressIndicator(Modifier.size(16.dp))else summary.fold(onSuccess={totals->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){PeriodMetric("收入",totals.incomeMinor);PeriodMetric("支出",totals.expenseMinor);PeriodMetric("结余",totals.netMinor)}},onFailure={Text(it.message?:"金额超出范围",color=ExpenseColor)})
                Text("${LedgerCalendar.format(range.first)} 至 ${LedgerCalendar.format(range.last)}",color=Muted,fontSize=9.sp)
            }
        }
    },actual={
        Column(verticalArrangement=Arrangement.spacedBy(9.dp)){
            SectionTitle("本期实际账单","记一笔",onNewEntry)
            if(entries==null)CircularProgressIndicator(Modifier.size(16.dp))
            else if(entries.isEmpty())EmptyHint("本期还没有实际账目，待发生计划不计入实际账单。")
            entries?.take(200)?.groupBy{it.epochDay}.orEmpty().forEach{(day,group)->Text(LedgerCalendar.format(day),color=Muted,fontSize=10.sp);ActualEntryCard(state,group,onDelete,onEntry,onRefund)}
            TextButton(onAllEntries){Text(if((entries?.size?:0)>200)"显示最近200条 · 查看全部 ${entries?.size?:0} 条 ›" else "全部账目 / 搜索 ›",fontSize=11.sp)}
        }
    })
}
@Composable private fun PeriodMetric(label:String,value:Long)=Column{Text(label,color=Muted,fontSize=9.sp);Text(cashMoney(value),fontWeight=FontWeight.Bold,fontSize=12.sp)}
fun Entry.toBill(state:LedgerState)=CashflowBill(id,epochDay,(if(kind.isOrdinary())"" else kind.label()+" · ")+category+(if(subcategory.isBlank())"" else " / $subcategory"),state.accountName(accountId)+(if(kind==EntryKind.TRANSFER)" → ${state.accountName(targetAccountId)}" else ""),if(kind.isOutflow())-amountMinor else amountMinor,kind==EntryKind.TRANSFER,listOfNotNull(LocalClock.timeText(minuteOfDay,second),billNote(state).takeIf{it.isNotBlank()},tags.takeIf{it.isNotBlank()},if(!affectsBalance||fundsDetached||sequence<=state.fundingBoundary)"历史已隔离 · 不改余额" else null,if(planId!=null)"已确认计划" else null).joinToString(" · "),currencyCode)

internal fun Entry.billNote(state: LedgerState): String {
    val planTitle = planId?.let { id -> state.plans.firstOrNull { it.id == id }?.title }
    if (planTitle == null) return note
    if (note.isBlank() || note == RichLedger.CASHFLOW_IMPORT_NOTE || note == planTitle) return planTitle
    return "$planTitle · $note"
}
