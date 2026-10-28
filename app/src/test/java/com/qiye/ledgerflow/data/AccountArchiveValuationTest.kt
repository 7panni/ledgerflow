package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.cashflow.data.*
import com.qiye.ledgerflow.importer.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

/** Self-created data only. No user files or device access. */
class AccountArchiveValuationTest {
    private val day=LedgerCalendar.parse("2026-10-24")
    private val codec=JsonLedgerCodec()
    private fun reject(fn:()->Unit){var failed=false;try{fn()}catch(_:Exception){failed=true};assertTrue("Expected rejection",failed)}
    private fun closed(interest:Long=0):LedgerState {
        var s=RichLedger.createDebt(LedgerState(),DebtKind.BORROWED,"自制甲",10000,day-2,null,day+8,interest,"自制本金",day)
        s=RichLedger.settleDebt(s,s.debts.single().id,10000,0,day,0,null,day,"self-close")
        return s
    }
    @Test fun archiveIsExplicitAndNewLoanOrUnpaidInterestReturnsToView(){
        val before=closed();val key=DebtGroups.all(before).single().key
        assertFalse(DebtGroups.isArchived(before,DebtGroups.all(before).single()))
        val s=DebtGroups.archive(before,key);assertTrue(DebtGroups.isArchived(s,DebtGroups.all(s).single()))
        assertEquals(before.entries,s.entries);assertEquals(before.debts,s.debts);assertEquals(LedgerEngine.balances(before),LedgerEngine.balances(s));assertEquals(LedgerForecast.forecast(before,day,30),LedgerForecast.forecast(s,day,30))
        assertFalse(DebtGroups.isArchived(DebtGroups.restoreArchive(s,key),DebtGroups.all(s).single()))
        val added=RichLedger.createDebt(s,DebtKind.BORROWED,"自制甲",2000,day,null,day+9,0,"新本金",day)
        assertFalse(DebtGroups.isArchived(added,DebtGroups.all(added).single()))
        val unpaid=closed(500);reject{DebtGroups.archiveAll(unpaid).let{assertTrue(it.debtArchives.isEmpty());DebtGroups.archive(it,DebtGroups.all(it).single().key)}}
        val deleted=LedgerEngine.deleteEntry(s,s.entries.last().id);assertTrue(deleted.debtArchives.isEmpty())
    }
    @Test fun archiveNativeBookAndFullRestoreAndOldSchemaDefault(){
        val s=DebtGroups.archiveAll(closed());assertEquals(1,s.debtArchives.size);assertEquals(s,codec.decode(codec.encode(s)))
        val old=JSONObject(codec.encode(s)).put("schemaVersion",5);old.remove("debtArchives");assertTrue(codec.decode(old.toString()).debtArchives.isEmpty())
        val cleared=DataModules.clearBook(s,day);assertTrue(cleared.debtArchives.isEmpty())
        val exported=ModuleExchange.nativeExport(s,DataModule.BOOK,day);val p=ModuleExchange.nativePreview(exported.bytes.toString(Charsets.UTF_8),DataModule.BOOK)
        val restored=DataModules.restore(cleared,p.data,DataModule.BOOK,day,p.key);assertTrue(DebtGroups.isArchived(restored,DebtGroups.all(restored).single()))
        val flowCleared=DataModules.clearFlow(s);assertEquals(s.debtArchives,flowCleared.debtArchives);assertTrue(DebtGroups.isArchived(flowCleared,DebtGroups.all(flowCleared).single()))
    }
    private fun history(bank:String):ImportPreview {
        val row=TableImporter.HEADERS.map{when(it){"日期"->"2026-10-20 12:30:45";"金额"->"12";"类型"->"支出";"分类"->"自制分类";"账户"->bank;else->""}}
        val raw=(listOf(TableImporter.HEADERS,row)).joinToString("\n"){it.joinToString(",")}.toByteArray()
        return TableImporter().preview(raw,"self.csv",LedgerState(),day)
    }
    @Test fun historyImportsDoNotCreateSelectableAccountsAndLiveImportRequiresMapping(){
        val p=history("自制银行");val s=LedgerImport.commit(LedgerState(),p.ready,day).state
        assertEquals(1,s.accounts.size);assertFalse(AccountManagement.selectable(s.accounts.single()));assertEquals("自制银行",s.accounts.single().name);assertEquals(s.accounts.single().id,s.entries.single().accountId);assertEquals(0L,LedgerEngine.balances(s).overallMinor)
        reject{LedgerImport.commit(LedgerState(),p.ready,day,true)}
        val initial=LedgerEngine.addAccount(LedgerState(),"银行",5000);val id=initial.accounts.single().id
        val live=LedgerImport.commit(initial,p.ready,day,true,mapOf("自制银行" to id)).state
        assertEquals(1,live.accounts.size);assertEquals(3800L,LedgerEngine.balances(live).accountsMinor.getValue(id));assertEquals(-1200L,LedgerEngine.balances(live).overallMinor)
        val legacy=s.copy(accounts=s.accounts.map{it.copy(active=true,archived=false)})
        assertEquals(listOf(legacy.accounts.single().id),AccountManagement.historicalBanks(legacy).map{it.id})
        val cleaned=AccountManagement.cleanHistoricalBanks(legacy,day);assertFalse(AccountManagement.selectable(cleaned.accounts.single()));assertEquals(legacy.entries,cleaned.entries)
        assertTrue(AccountManagement.historicalBanks(legacy.copy(accounts=legacy.accounts.map{it.copy(name="银行")})).isEmpty())
        assertTrue(AccountManagement.historicalBanks(legacy.copy(accounts=legacy.accounts.map{it.copy(openingMinor=100)})).isEmpty())
    }
    @Test fun removingAccountPreservesOverallHistoryAndPlanPredictionWithoutMerging(){
        var s=LedgerEngine.addAccount(LedgerState(),"自制银行",5000);val id=s.accounts.single().id
        s=LedgerEngine.correctBalance(s,null,20000,day,0,"整体")
        s=LedgerEngine.saveEntry(s,EntryDraft(EntryKind.EXPENSE,1000,day,0,"自制分类",id),day)
        s=LedgerEngine.savePlan(s,PlanDraft("自制计划",EntryKind.EXPENSE,2000,day+2,"自制分类",id))
        val funds=LedgerEngine.balances(s);val prediction=LedgerForecast.forecast(s,day,30)
        val after=AccountManagement.retire(s,id,day);assertEquals(funds,LedgerEngine.balances(after));assertEquals(prediction.days.map{it.epochDay to (it.cnyBalanceMinor to it.usdBalanceMinor)},LedgerForecast.forecast(after,day,30).days.map{it.epochDay to (it.cnyBalanceMinor to it.usdBalanceMinor)});assertEquals(prediction.days.flatMap{it.events}.map{it.name to (it.amountMinor to it.currencyCode)},LedgerForecast.forecast(after,day,30).days.flatMap{it.events}.map{it.name to (it.amountMinor to it.currencyCode)});assertNull(after.plans.single().accountId);assertEquals(s.entries,after.entries);assertFalse(AccountManagement.selectable(after.accounts.single()));assertEquals(after,codec.decode(codec.encode(after)))
        var usd=RichLedger.saveAccount(LedgerState(),Account("",0,"自制美元甲",500,"USD"));val first=usd.accounts.single().id
        usd=LedgerEngine.savePlan(usd,PlanDraft("自制美元计划",EntryKind.EXPENSE,100,day+2,"自制分类",first,currencyCode="USD"))
        reject{AccountManagement.retire(usd,first,day)}
        usd=RichLedger.saveAccount(usd,Account("",0,"自制美元乙",700,"USD"));val last=usd.accounts.last().id
        val retired=AccountManagement.retire(usd,first,day,last);assertEquals(last,retired.plans.single().accountId);assertEquals(700L,LedgerEngine.balances(retired).accountsMinor.getValue(last));assertEquals(500L,LedgerEngine.balances(retired).accountsMinor.getValue(first))
    }
    @Test fun valuationSafeRoundingMissingZeroAndOverflow(){
        val inputs=listOf(Triple(100_000_000L,900_000_000_000_000L,1_000_000_000_000L),Triple(123_456_789L,666_666_666_666L,1_000_000_000_000L),Triple(-123_456_789L,666_666_666_666L,1_000_000_000_000L),Triple(1L,500_000L,1_000_000L),Triple(1L,0L,1_000_000L))
        inputs.forEach{(u,p,d)->val n=BigInteger.valueOf(u).abs().multiply(BigInteger.valueOf(p));val expected=n.add(BigInteger.valueOf(d/2)).divide(BigInteger.valueOf(d)).longValueExact()*(if(u<0)-1 else 1);assertEquals(expected,AssetValuation.amount(u,p,d))}
        reject{AssetValuation.amount(Money.LIMIT,Money.LIMIT,1)};reject{AssetValuation.amount(100,-1,1)}
        var s=RichLedger.saveAccount(LedgerState(),Account("",0,"自制数字币",0,"CRYPTO",AccountType.CRYPTO));s=RichLedger.saveHoldings(s,listOf(CryptoHoldingEntity("BTC",100_000_000)),s.accounts.single().id)
        assertNull(AssetValuation.crypto(s));assertEquals(0L,AssetValuation.crypto(s,mapOf("BTC" to 0L)))
        assertEquals(900_000_000_00L,AssetValuation.crypto(s,mapOf("BTC" to 900_000_000_000_000L)))
        val removed=AccountManagement.retire(s,s.accounts.single().id,day);assertEquals(0L,AssetValuation.crypto(removed));assertEquals(s.holdings,removed.holdings)
    }
    @Test fun valuationsAreIndependentAndFlowNativeRoundTripPreservesSettings(){
        var s=LedgerEngine.correctBalance(LedgerState(),null,123456,day,0,"自制整体")
        s=RichLedger.saveAccount(s,Account("",0,"自制美元",10000,"USD"));val before=LedgerEngine.balances(s);val forecast=LedgerForecast.forecast(s,day,30)
        val configured=AssetValuation.configure(s,7_300_000,mapOf("USDT" to 7_000_000,"BTC" to 700_000_000_000),true,true)
        assertEquals(73000L,AssetValuation.usd(configured));assertEquals(before,LedgerEngine.balances(configured));assertEquals(forecast,LedgerForecast.forecast(configured,day,30));assertEquals(configured,codec.decode(codec.encode(configured)))
        reject{AssetValuation.configure(configured,0,emptyMap(),true,true)};reject{AssetValuation.configure(configured,7_300_000,mapOf("BTC" to -1L),true,true)}
        val exported=ModuleExchange.nativeExport(configured,DataModule.FLOW,day);val p=ModuleExchange.nativePreview(exported.bytes.toString(Charsets.UTF_8),DataModule.FLOW)
        val restored=DataModules.restore(DataModules.clearFlow(configured),p.data,DataModule.FLOW,day,p.key);assertEquals(configured.settings.cashflow,restored.settings.cashflow)
        val legacy=ModuleExchange.legacyFlowExport(configured,day);assertEquals(1,BackupJson.decode(legacy.bytes.toString(Charsets.UTF_8)).accounts.count{it.currencyCode=="USD"})
    }
}
