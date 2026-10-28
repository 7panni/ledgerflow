package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.cashflow.data.*
import com.qiye.ledgerflow.importer.*
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Invented records only; never reads private user exports. */
class FeedbackFixesTest {
    private val today=LedgerCalendar.parse("2026-02-15")
    private val codec=JsonLedgerCodec()
    private fun row(kind:String,amount:String,day:String="2026-02-01",person:String="自制对方",note:String="")=TableImporter.HEADERS.map{when(it){"日期"->"$day 12:30:45";"金额"->amount;"类型"->"债务";"分类"->kind;"债务人"->person;"备注"->note;"账户"->"无账户";else->""}}
    private fun csv(rows:List<List<String>>)=(listOf(TableImporter.HEADERS)+rows).joinToString("\r\n"){it.joinToString(","){v->"\""+v.replace("\"","\"\"")+"\""}}.toByteArray()
    private fun preview(rows:List<List<String>>,s:LedgerState=LedgerState())=TableImporter().preview(csv(rows),"invented.csv",s,today)
    @Test fun cnyCalibrationShiftsOverallOnlyByDeltaAndForeignNeverRevaluesCny(){
        var s=LedgerEngine.addAccount(LedgerState(),"自制银行",20000);val cny=s.accounts.single().id
        s=RichLedger.saveAccount(s,Account("",0,"自制美元",1000,"USD"));val usd=s.accounts.last().id
        s=LedgerEngine.correctBalance(s,null,100000,today,0,"自制整体")
        s=LedgerEngine.calibrateAccount(s,cny,35000,today,1,"校准")
        assertEquals(115000L,LedgerEngine.balances(s).overallMinor);assertEquals(35000L,LedgerEngine.balances(s).accountsMinor.getValue(cny))
        assertEquals(80000L,LedgerEngine.balances(s).overallMinor-LedgerEngine.balances(s).accountsMinor.getValue(cny))
        assertEquals(s,LedgerEngine.calibrateAccount(s,cny,35000,today,2,"重复提交"))
        s=LedgerEngine.calibrateAccount(s,usd,9000,today,3,"美元校准");assertEquals(115000L,LedgerEngine.balances(s).overallMinor)
        assertEquals(0,s.entries.size);assertEquals(s,codec.decode(codec.encode(s)))
    }
    @Test fun unifiedMonthPreservesUsedRemainingFundsAndShortMonthAnchor(){
        var s=LedgerEngine.correctBalance(LedgerState(),null,100000,today,0,"基点")
        s=RichLedger.saveBudget(s,BudgetEntity(name="自制月预算",totalCents=60000,remainingCents=60000,period=BudgetPeriod.MONTHLY,startDateEpochDay=LedgerCalendar.parse("2026-02-01"),endDateEpochDay=LedgerCalendar.parse("2026-02-28"),accountName=""))
        val id=s.budgets.single().id
        s=LedgerEngine.saveEntry(s,EntryDraft(EntryKind.EXPENSE,10000,today,0,"自制支出",budgetId=id),today)
        s=RichLedger.saveBudget(s,BudgetEntity(name="自制自定义",totalCents=900,remainingCents=800,period=BudgetPeriod.CUSTOM,startDateEpochDay=today,endDateEpochDay=today+9,accountName=""))
        val custom=s.budgets.last();val funds=LedgerEngine.balances(s)
        s=s.copy(settings=s.settings.copy(monthStartDay=1,cashflow=s.settings.cashflow.copy(budgetMonthStartDay=10)))
        s=BudgetRules.unifyMonthStart(s,31,today);val b=s.budgets.first{it.id==id}
        assertEquals(funds,LedgerEngine.balances(s));assertEquals(custom,s.budgets.last())
        assertEquals(31,s.settings.monthStartDay);assertEquals(31,s.settings.cashflow.budgetMonthStartDay)
        assertEquals(10000L,BudgetRules.spent(s,b,BudgetRules.cycle(b,today)!!));assertEquals(50000L,BudgetRules.remaining(s,b,today))
        assertEquals("2026-02-28",LedgerCalendar.format(BudgetRules.cycle(b,LedgerCalendar.parse("2026-03-01"))!!.first))
        assertEquals("2026-03-31",LedgerCalendar.format(BudgetRules.cycle(b,LedgerCalendar.parse("2026-04-01"))!!.first))
        s=RichLedger.saveBudget(s,b.copy(name="只改名"),false,today);assertEquals(50000L,BudgetRules.remaining(s,s.budgets.first{it.id==id},today))
        val repeat=BudgetRules.unifyMonthStart(s,31,today);assertEquals(50000L,BudgetRules.remaining(repeat,repeat.budgets.first{it.id==id},today))
        assertEquals(repeat,codec.decode(codec.encode(repeat)))
        val cleared=DataModules.clearBook(repeat,today);assertEquals(50000L,BudgetRules.remaining(cleared,cleared.budgets.first{it.id==id},today))
    }
    @Test fun multipleSamePersonPrincipalsAndUnknownPersonStaySeparateWithPendingRepayment(){
        val a=row("借入","100");val rows=listOf(a,a,row("借出","300"),row("借入","80",person=""),row("还款","40","2026-02-02"),row("收回","50","2026-02-03"))
        val p=preview(rows);assertEquals(6,p.ready.size);assertEquals(0,p.count(ImportStatus.UNSUPPORTED))
        var s=LedgerImport.commit(LedgerState(),p.ready,today).state
        assertEquals(4,s.debts.size);assertEquals(0,LedgerImport.pendingSettlements(s).size)
        val known=s.debts.filter{it.kind==DebtKind.BORROWED&&it.person=="自制对方"};assertEquals(2,known.size);assertTrue(known.none{LedgerImport.uncertain(s,it)})
        assertEquals(25000L,RichLedger.outstanding(s,s.debts.single{it.kind==DebtKind.LENT}.id));assertEquals(1,s.debts.count{it.person=="未提供对方"})
        assertEquals(PeriodSummary(0,0),LedgerEngine.periodSummary(s,LedgerCalendar.firstDay..today));assertEquals(0L,LedgerEngine.balances(s).overallMinor)
        assertEquals(6000L,RichLedger.outstanding(s,known.first().id));assertTrue(LedgerImport.pendingSettlements(s).isEmpty())
        assertEquals(s,codec.decode(codec.encode(s)))
        assertEquals(0,preview(rows.reversed(),s).ready.size)
        val more=rows+listOf(a);val extra=preview(more,s);assertEquals(1,extra.ready.size)
        val newer=LedgerImport.commit(s,extra.ready,today).state;assertEquals(5,newer.debts.size);assertEquals(0,preview(more.reversed(),newer).ready.size)
    }
    @Test fun reimportCanFillFormerlySkippedPrincipalsWithoutDuplicatingExistingDebt(){
        val first=row("借入","100",note="首笔");var s=LedgerImport.commit(LedgerState(),preview(listOf(first)).ready,today).state
        val existing=s.debts.single();val rows=listOf(first,row("借入","70",note="第二笔"),row("借出","90"))
        val p=preview(rows,s);assertEquals(2,p.ready.size);s=LedgerImport.commit(s,p.ready,today).state
        assertEquals(3,s.debts.size);assertTrue(existing in s.debts);assertEquals(3,s.entries.size)
        assertEquals(0,preview(rows,s).ready.size)
    }
    @Test fun uniquePartialRepaymentsLinkButAmbiguousAmountsNeverInventAllocation(){
        val rows=listOf(row("借入","100"),row("还款","30","2026-02-02"),row("还款","20","2026-02-03"))
        val s=LedgerImport.commit(LedgerState(),preview(rows).ready,today).state
        assertEquals(5000L,RichLedger.outstanding(s,s.debts.single().id));assertTrue(LedgerImport.pendingSettlements(s).isEmpty());assertEquals(0,preview(rows,s).ready.size)
        val orphan=preview(listOf(row("还款","20")));assertEquals(1,orphan.ready.size)
        val p=LedgerImport.commit(LedgerState(),orphan.ready,today).state;assertEquals(0,p.debts.size);assertEquals(1,LedgerImport.pendingSettlements(p).size)
    }
    @Test fun oldSchema4LoadsWithoutChangingBalancesAndUpgradeProtectsOriginal(){
        var s=LedgerEngine.addAccount(LedgerState(),"自制具体银行",12345)
        s=RichLedger.saveAccount(s,s.accounts.single().copy(accountType=AccountType.CMB))
        val root=JSONObject(codec.encode(s)).put("schemaVersion",4);root.getJSONArray("entries").let{};val raw=root.toString()
        assertEquals(s,codec.decode(raw))
        val dir=Files.createTempDirectory(File("build").toPath(),"schema4-feedback-").toFile()
        try{val store=AtomicLedgerStore(dir);store.dataFile.writeText(raw);val repo=LedgerRepository(store);repo.open();repo.mutate{it.copy(settings=it.settings.copy(monthStartDay=2))};assertEquals(raw,store.latestRecoveryFile()!!.readText());assertEquals(7,JSONObject(store.dataFile.readText()).getInt("schemaVersion"));assertEquals("自制具体银行",repo.snapshot().accounts.single().name)}finally{dir.deleteRecursively()}
    }
}
