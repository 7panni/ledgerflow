package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.cashflow.data.*
import com.qiye.ledgerflow.importer.*
import org.junit.Assert.*
import org.junit.Test

class DebtGroupsTest {
    private val today=LedgerCalendar.parse("2026-03-05")
    private fun loans():LedgerState {
        var s=RichLedger.createDebt(LedgerState(),DebtKind.BORROWED,"自制甲",10000,today-3,null,today+3,0,"第一笔",today)
        s=RichLedger.createDebt(s,DebtKind.BORROWED,"自制甲",20000,today-2,null,today+4,0,"第二笔",today)
        return s
    }
    private fun historyPayment(s:LedgerState,amount:Long,day:Long=today,person:String="自制甲",kind:EntryKind=EntryKind.REPAY):LedgerState{
        val next=LedgerEngine.saveEntry(s,EntryDraft(kind,amount,day,0,"自制本金",affectsBalance=false,affectsBudget=false),today)
        return next.copy(entries=next.entries.map{if(it.id==next.entries.last().id)it.copy(debtCounterparty=person)else it})
    }
    @Test fun oldUnlinkedPaymentSplitsAcrossLoansAndForecastUsesSameRemaining(){
        val initial=loans();val funds=LedgerEngine.balances(initial)
        val s=historyPayment(initial,15000);val g=DebtGroups.all(s).single()
        assertEquals(30000L,g.principalMinor);assertEquals(15000L,g.repaidMinor);assertEquals(15000L,g.remainingMinor)
        assertEquals(listOf(0L,15000L),s.debts.map{RichLedger.outstanding(s,it.id)})
        assertEquals(funds,LedgerEngine.balances(s));assertEquals(PeriodSummary(0,0),LedgerEngine.periodSummary(s,LedgerCalendar.firstDay..today))
        val forecast=LedgerForecast.forecast(s,today,10);assertEquals(15000L,forecast.endCnyMinor)
        assertEquals(initial.debts,s.debts);assertEquals(initial.entries,s.entries.take(initial.entries.size))
        val next=historyPayment(s,5000);assertEquals(10000L,DebtGroups.all(next).single().remainingMinor)
        assertEquals(next,JsonLedgerCodec().decode(JsonLedgerCodec().encode(next)))
    }
    @Test fun manualGroupRepaymentIsOneMoneyActionAndUndoAndRestartAreConsistent(){
        val initial=loans();val key=DebtGroups.all(initial).single().key
        val s=DebtGroups.repay(initial,key,25000,today,0,null,"自制归还",today,"self-request")
        assertEquals(5000L,LedgerEngine.balances(s).overallMinor);assertEquals(5000L,DebtGroups.all(s).single().remainingMinor)
        assertEquals(initial.entries.size+1,s.entries.size);assertEquals(s,DebtGroups.repay(s,key,25000,today,0,null,"自制归还",today,"self-request"))
        assertEquals(0L,LedgerEngine.periodSummary(s,LedgerCalendar.firstDay..today).expenseMinor)
        assertEquals(0L,LedgerForecast.forecast(s,today,10).endCnyMinor)
        val undo=RichLedger.undoSettlement(s,"self-request");assertEquals(initial,undo.copy(nextSequence=initial.nextSequence))
    }
    @Test fun separateDirectionsCurrenciesUnknownPeopleAndInvalidRemainders(){
        var s=loans();s=RichLedger.createDebt(s,DebtKind.LENT,"自制甲",4000,today-1,null,null,0,"",today)
        s=RichLedger.saveAccount(s,Account("",0,"自制美元",0,"USD"));s=RichLedger.createDebt(s,DebtKind.BORROWED,"自制甲",5000,today-1,s.accounts.last().id,null,0,"",today)
        s=historyPayment(s,35000);s=historyPayment(s,2000,today-5);s=historyPayment(s,1000,person="")
        val groups=DebtGroups.all(s);assertEquals(4,groups.size)
        val cny=groups.single{it.kind==DebtKind.BORROWED&&it.currencyCode=="CNY"&&it.person=="自制甲"}
        assertEquals(0L,cny.remainingMinor);assertEquals(7000L,cny.unmatched.sumOf{it.amountMinor})
        assertEquals(5000L,groups.single{it.currencyCode=="USD"}.remainingMinor)
        assertEquals(4000L,groups.single{it.kind==DebtKind.LENT}.remainingMinor)
        assertEquals(1000L,groups.single{it.person=="对方未填写"}.unmatched.sumOf{it.amountMinor})
    }
    @Test fun importReorderingAndReimportDoNotDoubleAllocateAndMonthlyPreviewKeeps31(){
        fun row(kind:String,amount:String)=TableImporter.HEADERS.map{when(it){"类型"->"债务";"分类"->kind;"债务人"->"自制乙";"金额"->amount;"日期"->"2026-03-01 12:00:00";else->""}}
        val rows=listOf(row("还款","150"),row("借入","100"),row("借入","100"))
        fun preview(list:List<List<String>>,s:LedgerState):ImportPreview{val bytes=(listOf(TableImporter.HEADERS)+list).joinToString("\n"){it.joinToString(",")}.toByteArray();return TableImporter().preview(bytes,"self.csv",s,today)}
        val s=LedgerImport.commit(LedgerState(),preview(rows,LedgerState()).ready,today).state
        assertEquals(5000L,DebtGroups.all(s).single().remainingMinor);assertEquals(0,preview(rows.reversed(),s).ready.size)
        assertEquals(2,s.debts.size);assertEquals(3,s.entries.size)
        val b=BudgetEntity(name="自制月预算",totalCents=10000,remainingCents=10000,period=BudgetPeriod.MONTHLY,startDateEpochDay=LedgerCalendar.parse("2026-02-28"),endDateEpochDay=LedgerCalendar.parse("2026-03-30"),accountName="",monthStartDay=31)
        assertEquals(31,BudgetRules.monthlyStartDay(b));assertEquals("2026-03-31",LedgerCalendar.format(BudgetRules.cycle(b,LedgerCalendar.parse("2026-04-01"))!!.first))
    }
}
