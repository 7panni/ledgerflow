package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.cashflow.data.*
import com.qiye.ledgerflow.importer.*
import com.qiyue.cashflow.shared.CoreRepeatRule
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.security.MessageDigest

class DataModulesTest {
    private val today=LedgerCalendar.parse("2026-10-22")
    private val codec=JsonLedgerCodec()
    private fun sample():LedgerState {
        var s=LedgerEngine.correctBalance(LedgerState(),null,100000,today,0,"自制整体")
        s=RichLedger.saveAccount(s,Account("",0,"自制甲",50000));val account=s.accounts.last().id
        s=RichLedger.saveAccount(s,Account("",0,"自制美元",20000,"USD",AccountType.PAYPAL))
        s=RichLedger.saveBudget(s,BudgetEntity(name="自制预算",totalCents=60000,remainingCents=60000,period=BudgetPeriod.MONTHLY,startDateEpochDay=today,endDateEpochDay=today+30,accountName="自制甲",carryOver=true));val budget=s.budgets.single().id
        s=LedgerImport.saveCategorizedEntry(s,EntryDraft(EntryKind.EXPENSE,10000,today,600,"自制父",account,note="自制备注",second=31,subcategory="自制子",budgetId=budget),today)
        s=LedgerEngine.savePlan(s,PlanDraft("自制非连续周期",EntryKind.EXPENSE,5000,today,"自制父",account,repeatRule=CoreRepeatRule.WEEKLY,usesBudget=true,budgetId=budget));val p=s.plans.last()
        s=RichLedger.confirmOccurrence(s,p.id,today,today,600,today);s=RichLedger.confirmOccurrence(s,p.id,today+14,today,601,today)
        s=RichLedger.createDebt(s,DebtKind.BORROWED,"自制债务人",10000,today,account,today+10,1000,"自制债务",today)
        s=RichLedger.settleDebt(s,s.debts.single().id,4000,500,today,610,null,today,"self-settlement")
        return s
    }
    private fun funds(s:LedgerState)=LedgerEngine.balances(s)
    private fun forecast(s:LedgerState)=LedgerForecast.forecast(s,today,180)
    private fun reject(fn:()->Unit){try{fn();fail("Should reject")}catch(_:Exception){}}
    @Test fun clearBookKeepsMoneyBudgetNonContinuousOccurrencesAndDebtFuture(){
        val before=sample();val after=DataModules.clearBook(before,today)
        assertTrue(after.entries.isEmpty());assertTrue(after.debts.isEmpty());assertEquals(before.categories,after.categories)
        assertEquals(funds(before),funds(after));assertEquals(forecast(before),forecast(after))
        assertEquals(BudgetRules.remaining(before,before.budgets.single(),today),BudgetRules.remaining(after,after.budgets.single(),today))
        assertEquals(2,after.settledOccurrences.size);val p=after.plans.first{it.title=="自制非连续周期"};assertEquals(after,RichLedger.confirmOccurrence(after,p.id,today+14,today,0,today))
        assertTrue(RichLedger.pending(after,today+21).none{it.plan.id==p.id&&it.originalEpochDay==today+14});assertTrue(RichLedger.pending(after,today+21).any{it.plan.id==p.id&&it.originalEpochDay==today+7})
        assertTrue(after.plans.all{it.debtId==null});assertTrue(after.plans.any{it.kind==EntryKind.REPAY&&it.fundingCapMinor==6000L});assertTrue(after.plans.any{it.isInterest&&it.fundingCapMinor==500L})
        val principal=after.plans.first{it.kind==EntryKind.REPAY};val paid=RichLedger.confirmOccurrence(after,principal.id,principal.dueEpochDay,today,0,today);assertEquals(0,LedgerEngine.periodSummary(paid,today..today).expenseMinor)
        assertEquals(after,codec.decode(codec.encode(after)));assertEquals(after,DataModules.clearBook(after,today))
    }
    @Test fun budgetCarryAndExplicitRecalibrationDoNotDoubleCountFrozenSpend(){
        val before=sample();val after=DataModules.clearBook(before,today);val b=after.budgets.single()
        assertEquals(BudgetRules.remaining(before,b,today+40),BudgetRules.remaining(after,b,today+40))
        val renamed=RichLedger.saveBudget(after,b.copy(name="自制改名"),false,today);assertEquals(BudgetRules.remaining(after,b,today),BudgetRules.remaining(renamed,renamed.budgets.single(),today))
        val calibrated=RichLedger.saveBudget(after,b.copy(remainingCents=30000),true,today);assertEquals(30000,BudgetRules.remaining(calibrated,calibrated.budgets.single(),today))
    }
    @Test fun clearFlowPreservesDebtHistoryAndOldEditsCannotReopenFunds(){
        val before=sample();var s=DataModules.clearFlow(before)
        assertEquals(before.debts,s.debts);assertEquals(before.categories,s.categories);assertEquals(before.entries.map{listOf(it.kind,it.amountMinor,it.epochDay,it.minuteOfDay,it.second,it.note)},s.entries.map{listOf(it.kind,it.amountMinor,it.epochDay,it.minuteOfDay,it.second,it.note)})
        assertTrue(s.plans.isEmpty());assertTrue(s.budgets.isEmpty());assertTrue(s.accounts.all{it.archived});assertEquals(0,funds(s).overallMinor);assertTrue(funds(s).accountsMinor.values.all{it==0L});assertEquals(6000,RichLedger.outstanding(s,s.debts.single().id))
        assertEquals(0,forecast(s).endCnyMinor);val old=s.entries.first{it.kind==EntryKind.EXPENSE&&!it.isInterest};s=LedgerEngine.saveEntry(s,EntryDraft(old.kind,20000,old.epochDay,old.minuteOfDay,old.category,old.accountId,note="自制改旧账"),today,old.id);assertEquals(0,funds(s).overallMinor)
        s=LedgerEngine.saveEntry(s,EntryDraft(EntryKind.EXPENSE,2000,today,0,"自制新账"),today);assertEquals(-2000,funds(s).overallMinor);assertTrue(s.plans.isEmpty());assertEquals(s,codec.decode(codec.encode(s)))
    }
    @Test fun cryptoTransferAndForeignCurrentBalancesSurviveBookClear(){
        var s=sample();s=RichLedger.saveAccount(s,Account("",0,"自制币甲",0,"CRYPTO",AccountType.CRYPTO));val a=s.accounts.last().id;s=RichLedger.saveAccount(s,Account("",0,"自制币乙",0,"CRYPTO",AccountType.CRYPTO));val b=s.accounts.last().id
        s=RichLedger.saveHoldings(s,listOf(CryptoHoldingEntity("BTC",100_000_000)),a);s=LedgerEngine.saveEntry(s,EntryDraft(EntryKind.TRANSFER,20_000_000,today,0,"转币",a,b,currencyCode="BTC",affectsBudget=false),today)
        val clear=DataModules.clearBook(s,today);assertEquals(funds(s),funds(clear));assertEquals(RichLedger.cryptoAccountBalances(s,a,"BTC"),RichLedger.cryptoAccountBalances(clear,a,"BTC"));assertEquals(RichLedger.cryptoAccountBalances(s,b,"BTC"),RichLedger.cryptoAccountBalances(clear,b,"BTC"))
    }
    @Test fun nativeBookRoundTripOnlyReplacesBookAndDoesNotMoveCashflow(){
        val before=sample();val export=ModuleExchange.nativeExport(before,DataModule.BOOK,today);val parsed=ModuleExchange.nativePreview(export.bytes.toString(Charsets.UTF_8),DataModule.BOOK)
        assertTrue(parsed.data.plans.isEmpty());assertTrue(parsed.data.corrections.isEmpty());val cleared=DataModules.clearBook(before,today)
        val restored=DataModules.restore(cleared,parsed.data,DataModule.BOOK,today,parsed.key)
        assertEquals(funds(cleared),funds(restored));assertEquals(forecast(cleared),forecast(restored));assertEquals(before.entries.map{it.amountMinor},restored.entries.map{it.amountMinor});assertEquals(before.entries.map{it.note},restored.entries.map{it.note});assertEquals(6000,RichLedger.outstanding(restored,restored.debts.single().id));assertTrue(restored.entries.all{it.fundsDetached});assertEquals(restored,DataModules.restore(restored,parsed.data,DataModule.BOOK,today,parsed.key));assertEquals(restored,codec.decode(codec.encode(restored)))
    }
    @Test fun nativeFlowRoundTripOnlyReplacesFlowAndKeepsBookAndForecast(){
        val before=sample();val export=ModuleExchange.nativeExport(before,DataModule.FLOW,today);val parsed=ModuleExchange.nativePreview(export.bytes.toString(Charsets.UTF_8),DataModule.FLOW)
        assertTrue(parsed.data.entries.isEmpty());assertTrue(parsed.data.debts.isEmpty());val cleared=DataModules.clearFlow(before)
        val restored=DataModules.restore(cleared,parsed.data,DataModule.FLOW,today,parsed.key)
        assertEquals(before.entries.map{it.amountMinor to it.note},restored.entries.map{it.amountMinor to it.note});assertEquals(before.debts,restored.debts);assertEquals(funds(before).overallMinor,funds(restored).overallMinor)
        assertEquals(forecast(before).days.map{it.cnyBalanceMinor to it.usdBalanceMinor},forecast(restored).days.map{it.cnyBalanceMinor to it.usdBalanceMinor});assertEquals(2,restored.settledOccurrences.size);assertEquals(restored,codec.decode(codec.encode(restored)))
    }
    @Test fun corruptWrongModuleAndCrossDomainPayloadReject(){
        val state=sample();val good=ModuleExchange.nativeExport(state,DataModule.BOOK,today).bytes.toString(Charsets.UTF_8)
        reject{ModuleExchange.nativePreview(good,DataModule.FLOW)};reject{ModuleExchange.nativePreview(good+"x",DataModule.BOOK)}
        val altered=JSONObject(good);altered.getJSONObject("payload").put("nextSequence",1);reject{ModuleExchange.nativePreview(altered.toString(),DataModule.BOOK)}
        reject{DataModules.restore(state,state,DataModule.BOOK,today,"a".repeat(64))}
    }
    private fun simpleBook():LedgerState {
        var s=LedgerEngine.addAccount(LedgerState(),"自制账户",0);val a=s.accounts.single().id
        s=LedgerImport.saveCategorizedEntry(s,EntryDraft(EntryKind.EXPENSE,2000,today,621,"自制父",a,note="自制换行\n逗号,和引号\"",second=31,subcategory="自制子"),today);val expense=s.entries.last()
        s=LedgerImport.saveCategorizedEntry(s,EntryDraft(EntryKind.REFUND,500,today,0,"自制父",a,subcategory="自制子",refundOfId=expense.id),today)
        s=LedgerImport.saveCategorizedEntry(s,EntryDraft(EntryKind.INCOME,1000,today,620,"自制父",second=45),today)
        s=RichLedger.createDebt(s,DebtKind.BORROWED,"自制对方",10000,today,a,null,0,"自制本金",today)
        return RichLedger.settleDebt(s,s.debts.single().id,10000,0,today,622,null,today,"self-close")
    }
    @Test fun csvAndXlsxUseSame22ColumnsAndRoundTripPrincipalAndCategories(){
        val s=simpleBook();val csv=ModuleExchange.tableExport(s,false);val xlsx=ModuleExchange.tableExport(s,true);val importer=TableImporter()
        val c=importer.preview(csv.bytes,"self.csv",LedgerState(),today);val x=importer.preview(xlsx.bytes,"self.xlsx",LedgerState(),today);assertEquals(0,c.count(ImportStatus.ERROR));assertEquals(0,c.count(ImportStatus.UNSUPPORTED));assertEquals(c.ready.map{it.key},x.ready.map{it.key})
        val restored=LedgerImport.commit(LedgerState(),x.ready,today).state;assertEquals(1500,LedgerEngine.periodSummary(restored,today..today).expenseMinor);assertEquals(1000,LedgerEngine.periodSummary(restored,today..today).incomeMinor);assertEquals(0,RichLedger.outstanding(restored,restored.debts.single().id));assertEquals(0,funds(restored).overallMinor)
        File("build/self-export-0.2.2.xlsx").writeBytes(xlsx.bytes)
    }
    @Test fun compatibilityRefusesForeignAndNonContinuousStatesAndLegacyReadsBalanceDifference(){
        reject{ModuleExchange.legacyFlowExport(sample(),today)}
        var s=LedgerEngine.correctBalance(LedgerState(),null,100000,today,0,"自制整体");s=LedgerEngine.addAccount(s,"自制账户",25000);s=LedgerEngine.savePlan(s,PlanDraft("自制未来",EntryKind.EXPENSE,1000,today+2,"自制项"))
        val legacy=ModuleExchange.legacyFlowExport(s,today);val decoded=BackupJson.decode(legacy.bytes.toString(Charsets.UTF_8));assertEquals(100000,decoded.accounts.filter{it.currencyCode=="CNY"&&it.isActive&&it.includeInForecast}.sumOf{it.currentBalanceCents});assertEquals(1,s.accounts.size)
        s=RichLedger.saveAccount(s,Account("",0,"自制USD",0,"USD"));s=LedgerEngine.saveEntry(s,EntryDraft(EntryKind.INCOME,100,today,0,"自制外币",s.accounts.last().id,currencyCode="USD"),today);reject{ModuleExchange.tableExport(s,false)}
    }
    @Test fun privateCurrentBookClearOnlyWhenExplicitlyAuthorized(){
        val source=System.getenv("LEDGER_PRIVATE_CLEAR_SOURCE");assumeTrue(source!=null);val file=File(source!!);val before=codec.decode(file.readText());val after=DataModules.clearBook(before,today)
        require(after.entries.isEmpty()&&after.debts.isEmpty()&&before.categories==after.categories){"Book clear counts/categories incorrect"};require(funds(before)==funds(after)&&forecast(before)==forecast(after)){"Book clear changed current funds/forecast"}
        require(before.budgets.map{BudgetRules.remaining(before,it,today)}==after.budgets.map{BudgetRules.remaining(after,it,today)}){"Book clear changed budget"};require(before.cashflowSources==after.cashflowSources){"Book clear changed source"}
        val raw=codec.encode(after);require(codec.decode(raw)==after){"Cleared candidate round trip failed"};val target=File(file.parentFile,"清记账候选-0.2.2.json");if(target.exists())require(target.readText()==raw){"Candidate differs; preserve existing"}else target.writeText(raw)
        fun summary(s:LedgerState):JSONObject {val f=forecast(s);return JSONObject().put("entries",s.entries.size).put("debts",s.debts.size).put("categories",s.categories.size).put("accounts",s.accounts.size).put("plans",s.plans.size).put("budgets",s.budgets.size).put("holdings",s.holdings.size).put("nativeOverallMinor",funds(s).overallMinor).put("nativeAccounts",JSONObject(funds(s).accountsMinor)).put("settled",JSONArray(s.settledOccurrences.map{JSONObject().put("planId",it.planId).put("originalEpochDay",it.originalEpochDay).put("amountMinor",it.amountMinor)})).put("planState",JSONArray(s.plans.map{JSONObject().put("id",it.id).put("active",it.active).put("postingFrom",it.postingFromEpochDay).put("installmentRemaining",it.installmentRemaining).put("fundingCapMinor",it.fundingCapMinor)})).put("budgetState",JSONArray(s.budgets.map{b->val cycle=BudgetRules.cycle(b,today);JSONObject().put("id",b.id).put("remaining",BudgetRules.remaining(s,b,today)).put("used",cycle?.let{BudgetRules.spent(s,b,it)}?:0)})).put("forecast",JSONArray(f.days.map{JSONObject().put("day",it.epochDay).put("cny",it.cnyBalanceMinor).put("usd",it.usdBalanceMinor)})).put("debtFuture",JSONArray(s.plans.filter{it.debtId!=null||it.kind in listOf(EntryKind.REPAY,EntryKind.COLLECT)||it.fundingCapMinor!=null}.map{JSONObject().put("id",it.id).put("kind",it.kind.name).put("cap",it.fundingCapMinor)})).put("fundingBoundary",s.fundingBoundary).put("frozenBudgetSpends",s.frozenBudgetSpends.size).put("corrections",s.corrections.size).put("cashflowSources",s.cashflowSources.size).put("nextSequence",s.nextSequence)}
        val hash=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it)}
        File(file.parentFile,"清记账只读核查-0.2.2.json").writeText(JSONObject().put("before",summary(before)).put("after",summary(after)).put("fundsAndForecastUnchanged",true).put("budgetAndSourcesUnchanged",true).put("candidateSha256",hash).put("phoneWritten",false).put("expectedDifferences","账目/债务/记账收据清空；必要时增加资金锚点、周期用量及独立兑现凭据；序号保留向前，非原始JSON相等").toString(2))
    }
}
