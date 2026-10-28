package com.qiye.ledgerflow.core

import com.qiye.ledgerflow.cashflow.data.*
import com.qiyue.cashflow.shared.CoreRepeatRule
import kotlin.test.*

class IntegrationRulesTest {
    private val today=LedgerCalendar.parse("2026-10-21")
    private fun initial()=LedgerEngine.correctBalance(LedgerState(),null,100_000,today,600,"自制整体基点")
    private fun budget(s:LedgerState,remaining:Long=60_000,name:String="自制预算",period:BudgetPeriod=BudgetPeriod.CUSTOM,carry:Boolean=false,method:BudgetMethod=BudgetMethod.DAILY_AVERAGE)=RichLedger.saveBudget(s,BudgetEntity(name=name,totalCents=60_000,remainingCents=remaining,period=period,startDateEpochDay=today,endDateEpochDay=if(period==BudgetPeriod.WEEKLY)today+6 else today+29,accountName="",method=method,chargeDay=if(method==BudgetMethod.SPECIFIC_DAY)23 else null,carryOver=carry))
    private fun expense(s:LedgerState,amount:Long=10_000,budgetId:Long?=null)=LedgerEngine.saveEntry(s,EntryDraft(EntryKind.EXPENSE,amount,today,600,"自制消费",budgetId=budgetId),today)

    @Test fun actualConsumptionReducesOnlyUnspentBudgetAndCalibrationIsAnAnchor(){
        var s=budget(initial());val id=s.budgets.single().id;s=expense(s)
        assertEquals(90_000,LedgerEngine.balances(s).overallMinor);assertEquals(50_000,BudgetRules.remaining(s,s.budgets.single(),today));assertEquals(40_000,LedgerForecast.forecast(s,today,30).endCnyMinor)
        s=RichLedger.saveBudget(s,s.budgets.single().copy(isActive=false),false);s=RichLedger.saveBudget(s,s.budgets.single().copy(isActive=true,name="改名"),false)
        assertEquals(50_000,BudgetRules.remaining(s,s.budgets.single(),today));assertEquals(id,s.entries.single().budgetId)
        s=RichLedger.saveBudget(s,s.budgets.single().copy(remainingCents=40_000),true,today)
        assertEquals(40_000,BudgetRules.remaining(s,s.budgets.single(),today));s=expense(s)
        assertEquals(30_000,BudgetRules.remaining(s,s.budgets.single(),today))
    }
    @Test fun temporaryPlanConfirmationPreservesForecastForBothBudgetAssignments(){
        for(uses in listOf(false,true)){
            var s=budget(initial());s=LedgerEngine.savePlan(s,PlanDraft("自制临时支出",EntryKind.EXPENSE,5_000,today,"测试",temporary=true,usesBudget=uses))
            val p=s.plans.single();val before=LedgerForecast.forecast(s,today,30).endCnyMinor
            assertEquals(if(uses)40_000 else 35_000,before)
            s=RichLedger.confirmOccurrence(s,p.id,today,today,600,today)
            assertEquals(before,LedgerForecast.forecast(s,today,30).endCnyMinor)
            assertEquals(if(uses)55_000 else 60_000,BudgetRules.remaining(s,s.budgets.single(),today))
        }
    }
    @Test fun multipleBudgetsHaveExplicitStableAssignment(){
        var s=budget(initial(),name="甲");val a=s.budgets.single().id;s=budget(s,name="乙");val b=s.budgets.last().id
        s=expense(s,1_000,b);assertEquals(60_000,BudgetRules.remaining(s,s.budgets.first{it.id==a},today));assertEquals(59_000,BudgetRules.remaining(s,s.budgets.first{it.id==b},today))
        s=expense(s,1_000);assertNull(s.entries.last().budgetId);assertEquals(59_000,BudgetRules.remaining(s,s.budgets.first{it.id==b},today))
        s=RichLedger.saveBudget(s,s.budgets.first{it.id==b}.copy(isActive=false),false);s=RichLedger.saveBudget(s,s.budgets.first{it.id==b}.copy(isActive=true),false)
        assertEquals(59_000,BudgetRules.remaining(s,s.budgets.first{it.id==b},today))
    }
    @Test fun carryOverAndSpecificDayUseOriginalCycleEngine(){
        var s=budget(initial(),period=BudgetPeriod.WEEKLY,carry=true);s=expense(s,10_000)
        assertEquals(110_000,BudgetRules.remaining(s,s.budgets.single(),today+7))
        s=budget(initial(),method=BudgetMethod.SPECIFIC_DAY)
        val f=LedgerForecast.forecast(s,today,3);assertEquals(100_000,f.days[1].cnyBalanceMinor);assertEquals(40_000,f.days[2].cnyBalanceMinor)
    }
    @Test fun recurringOccurrencesConfirmAndUndoIndependentlyWithoutReposting(){
        var s=LedgerEngine.savePlan(initial(),PlanDraft("自制日计划",EntryKind.EXPENSE,1_000,today,"测试",repeatRule=CoreRepeatRule.DAILY),today=today)
        val p=s.plans.single();s=RichLedger.confirmOccurrence(s,p.id,today,today,600,today)
        assertEquals(s,RichLedger.confirmOccurrence(s,p.id,today,today,601,today))
        s=RichLedger.confirmOccurrence(s,p.id,today+1,today,602,today);assertEquals(98_000,LedgerEngine.balances(s).overallMinor)
        s=RichLedger.undoOccurrence(s,p.id,today);assertEquals(1,s.entries.size);assertEquals(99_000,LedgerEngine.balances(s).overallMinor)
        s=RichLedger.postponeOccurrence(s,p.id,today,today+4);assertEquals(today+4,RichLedger.pending(s,today+4).first{it.originalEpochDay==today}.dueEpochDay)
    }
    @Test fun pastRecurringStartDoesNotBackfillAndAutoPostingIsOptIn(){
        var s=LedgerEngine.savePlan(initial(),PlanDraft("自制过去起始日",EntryKind.EXPENSE,1_000,today-60,"测试",repeatRule=CoreRepeatRule.DAILY),today=today)
        assertEquals(3,RichLedger.pending(s,today+2).size);assertEquals(s,RichLedger.automaticPost(s,today))
        s=s.copy(settings=s.settings.copy(cashflow=s.settings.cashflow.copy(automaticPosting=true,automaticPostingFromEpochDay=today)))
        s=RichLedger.automaticPost(s,today);assertEquals(1,s.entries.size);assertEquals(99_000,LedgerEngine.balances(s).overallMinor);assertEquals(s,RichLedger.automaticPost(s,today))
    }
    @Test fun installmentsConserveTotalIncludingLastCentAndPaidPeriodTail(){
        val start=LedgerCalendar.parse("2026-01-31")
        var s=LedgerEngine.savePlan(initial(),PlanDraft("自制三期",EntryKind.EXPENSE,333,start,"测试",repeatRule=CoreRepeatRule.MONTHLY,installmentTotal=3,installmentRemaining=3,installmentTotalAmountMinor=1_000),today=start)
        val p=s.plans.single();val rows=RichLedger.occurrences(s,p,start+100);assertEquals(listOf(333L,333L,334L),rows.map{it.amountMinor});assertEquals("2026-02-28",LedgerCalendar.format(rows[1].originalEpochDay))
        s=RichLedger.confirmOccurrence(s,p.id,rows[0].originalEpochDay,today,600,today);assertEquals(667,RichLedger.pending(s,start+100).sumOf{it.amountMinor})
    }
    @Test fun borrowedPrincipalAndPartialRepaymentInterestRemainSeparateAndTraceable(){
        var s=RichLedger.createDebt(initial(),DebtKind.BORROWED,"自制对方",10_000,today,null,today+1,500,"自制",today);val debt=s.debts.single()
        assertEquals(110_000,LedgerEngine.balances(s).overallMinor);assertEquals(0,LedgerEngine.periodSummary(s,today..today).incomeMinor)
        s=RichLedger.settleDebt(s,debt.id,4_000,500,today,600,null,today,"self-request")
        assertEquals(105_500,LedgerEngine.balances(s).overallMinor);assertEquals(6_000,RichLedger.outstanding(s,debt.id));assertEquals(0,RichLedger.interestOutstanding(s,debt.id));assertEquals(500,LedgerEngine.periodSummary(s,today..today).expenseMinor)
        assertEquals(99_500,LedgerForecast.forecast(s,today,3).endCnyMinor);assertEquals(s,RichLedger.settleDebt(s,debt.id,4_000,500,today,600,null,today,"self-request"))
        s=RichLedger.undoSettlement(s,"self-request");assertEquals(110_000,LedgerEngine.balances(s).overallMinor);assertEquals(10_000,RichLedger.outstanding(s,debt.id));assertEquals(0,LedgerEngine.periodSummary(s,today..today).expenseMinor)
        assertFails{RichLedger.settleDebt(s,debt.id,10_001,0,today,600,null,today,"overflow")}
    }
    @Test fun noDueDateDoesNotInventPlansAndLentCollectionIsNotIncome(){
        var s=RichLedger.createDebt(initial(),DebtKind.LENT,"自制对方",10_000,today,null,null,0,"",today);assertTrue(s.plans.isEmpty());val d=s.debts.single()
        s=RichLedger.settleDebt(s,d.id,4_000,500,today,600,null,today,"collect-request");assertEquals(94_500,LedgerEngine.balances(s).overallMinor);assertEquals(500,LedgerEngine.periodSummary(s,today..today).incomeMinor);assertEquals(6_000,RichLedger.outstanding(s,d.id))
    }
    @Test fun refundOffsetsExpenseAndUnspentBudgetWithoutBecomingIncome(){
        var s=expense(budget(initial()));val original=s.entries.single()
        s=LedgerEngine.saveEntry(s,EntryDraft(EntryKind.REFUND,2_000,today,601,"自制消费",refundOfId=original.id),today)
        assertEquals(92_000,LedgerEngine.balances(s).overallMinor);assertEquals(8_000,LedgerEngine.periodSummary(s,today..today).expenseMinor);assertEquals(0,LedgerEngine.periodSummary(s,today..today).incomeMinor);assertEquals(52_000,BudgetRules.remaining(s,s.budgets.single(),today))
        assertFails{LedgerEngine.saveEntry(s,EntryDraft(EntryKind.REFUND,9_000,today,602,"自制消费",refundOfId=original.id),today)}
    }
    @Test fun foreignBalancesAndRateChangesNeverRewriteIndependentCny(){
        var s=RichLedger.saveAccount(initial(),Account("",0,"自制美元甲",20_000,"USD",AccountType.PAYPAL));val a=s.accounts.single().id
        s=LedgerEngine.saveEntry(s,EntryDraft(EntryKind.INCOME,1_000,today,600,"收入",a),today)
        assertEquals(100_000,LedgerEngine.balances(s).overallMinor);assertEquals(21_000,LedgerForecast.forecast(s,today,2).currentUsdMinor);assertEquals(0,LedgerEngine.periodSummary(s,today..today).incomeMinor)
        s=s.copy(settings=s.settings.copy(cashflow=s.settings.cashflow.copy(usdRateMicros=9_000_000)));assertEquals(100_000,LedgerForecast.forecast(s,today,2).currentCnyMinor)
    }
    @Test fun cryptoTransferMovesAccountsWithoutChangingHoldingsOrCny(){
        var s=RichLedger.saveAccount(initial(),Account("",0,"自制币甲",0,"CRYPTO",AccountType.CRYPTO));s=RichLedger.saveAccount(s,Account("",0,"自制币乙",0,"CRYPTO",AccountType.CRYPTO));val a=s.accounts[0].id;val b=s.accounts[1].id
        s=RichLedger.saveHoldings(s,listOf(CryptoHoldingEntity("USDT",100_000_000)),a)
        s=LedgerEngine.saveEntry(s,EntryDraft(EntryKind.TRANSFER,40_000_000,today,600,"划转",a,b,currencyCode="USDT"),today)
        assertEquals(100_000_000,RichLedger.cryptoBalances(s)["USDT"]);assertEquals(60_000_000,RichLedger.cryptoAccountBalances(s,a,"USDT"));assertEquals(40_000_000,RichLedger.cryptoAccountBalances(s,b,"USDT"));assertEquals(100_000,LedgerEngine.balances(s).overallMinor)
    }
    @Test fun categoryRenameRetainsRelationshipsAndSafeDeleteProtectsHistory(){
        var s=LedgerImport.saveCategorizedEntry(initial(),EntryDraft(EntryKind.EXPENSE,100,today,600,"自制父",subcategory="自制子"),today);val child=s.categories.first{it.parentId!=null};val parent=s.categories.first{it.id==child.parentId}
        s=RichLedger.saveCategory(s,"新父名",EntryKind.EXPENSE,id=parent.id);assertEquals(child.id,s.entries.single().categoryId);assertEquals("新父名",s.entries.single().category)
        assertFails{RichLedger.deleteCategory(s,child.id)};s=RichLedger.saveCategory(s,child.name,child.kind,child.parentId,child.id,false)
        assertFails{LedgerEngine.saveEntry(s,EntryDraft(EntryKind.EXPENSE,100,today,601,"新父名",categoryId=child.id),today)}
    }
    @Test fun historicImportEditsAndDeletionNeverChangeCurrentBalanceOrBudget(){
        var s=budget(initial());val record=ImportRecord("a".repeat(64)+"#1",2,EntryKind.EXPENSE,10_000,today,600,0,"自制历史","","", "",null,null)
        s=LedgerImport.commit(s,listOf(record),today).state;val old=s.entries.single();assertFalse(old.affectsBalance);assertFalse(old.affectsBudget)
        s=LedgerEngine.saveEntry(s,EntryDraft(EntryKind.EXPENSE,20_000,today,600,"改历史",note="改备注"),today,old.id)
        assertFalse(s.entries.single().affectsBudget);assertEquals(100_000,LedgerEngine.balances(s).overallMinor);assertEquals(60_000,BudgetRules.remaining(s,s.budgets.single(),today))
        s=LedgerEngine.deleteEntry(s,old.id);assertEquals(100_000,LedgerEngine.balances(s).overallMinor);assertEquals(60_000,BudgetRules.remaining(s,s.budgets.single(),today))
    }
}
