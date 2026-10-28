package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.cashflow.data.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class DirectAccessTest {
    private val day=LedgerCalendar.parse("2026-10-24")
    private fun twoLoans(paid:Long):LedgerState {
        var s=RichLedger.createDebt(LedgerState(),DebtKind.BORROWED,"自制对象",10000,day-3,null,day+5,0,"自制甲项",day)
        s=RichLedger.createDebt(s,DebtKind.BORROWED,"自制对象",10000,day-2,null,day+7,0,"自制乙项",day)
        return DebtGroups.repay(s,DebtGroups.all(s).single().key,paid,day,0,null,"自制跨项归还",day,"self-payment")
    }
    @Test fun singleClosedProjectArchivesWhileSamePersonOtherLoanRemainsUnpaid(){
        val s=twoLoans(15000);val a=s.debts.first().id;val b=s.debts.last().id
        val after=DebtGroups.archiveProject(s,a)
        assertTrue(DebtGroups.isProjectArchived(after,a));assertFalse(DebtGroups.isProjectArchived(after,b));assertEquals(5000L,RichLedger.outstanding(after,b))
        assertEquals(s.entries,after.entries);assertEquals(s.debts,after.debts);assertEquals(LedgerEngine.balances(s),LedgerEngine.balances(after));assertEquals(LedgerForecast.forecast(s,day,30),LedgerForecast.forecast(after,day,30))
        val pa=DebtGroups.projectEntries(after,a).single{it.allocatedMinor>0};val pb=DebtGroups.projectEntries(after,b).single{it.allocatedMinor>0}
        assertEquals(pa.entry,pb.entry);assertEquals(15000L,pa.entry.amountMinor);assertEquals(10000L,pa.allocatedMinor);assertEquals(5000L,pb.allocatedMinor)
        assertFalse(DebtGroups.isProjectArchived(DebtGroups.restoreProject(after,a),a))
        val edited=LedgerEngine.validate(after.copy(entries=after.entries.map{if(it.id==pa.entry.id)it.copy(note="修改归还备注")else it}));assertFalse(DebtGroups.isProjectArchived(edited,a))
        val newer=RichLedger.createDebt(after,DebtKind.BORROWED,"自制对象",2000,day,null,null,0,"自制新项",day);assertFalse(DebtGroups.isProjectArchived(newer,newer.debts.last().id))
    }
    @Test fun oldGroupArchivesReadAndRestoreOneProjectWithoutUnarchivingOthers(){
        var s=twoLoans(20000);val g=DebtGroups.all(s).single();s=s.copy(debtArchives=listOf(DebtArchive(g.loans.map{it.id},g.entries.map{it.id})))
        assertTrue(s.debts.all{DebtGroups.isProjectArchived(s,it.id)})
        val restored=DebtGroups.restoreProject(s,s.debts.first().id);assertFalse(DebtGroups.isProjectArchived(restored,s.debts.first().id));assertTrue(DebtGroups.isProjectArchived(restored,s.debts.last().id))
        val codec=JsonLedgerCodec();assertEquals(restored,codec.decode(codec.encode(restored)))
        val p=ModuleExchange.nativePreview(ModuleExchange.nativeExport(restored,DataModule.BOOK,day).bytes.toString(Charsets.UTF_8),DataModule.BOOK)
        val book=DataModules.restore(DataModules.clearBook(restored,day),p.data,DataModule.BOOK,day,p.key)
        assertEquals(1,book.debts.count{DebtGroups.isProjectArchived(book,it.id)});assertEquals(1,DataModules.clearFlow(restored).debts.count{DebtGroups.isProjectArchived(DataModules.clearFlow(restored),it.id)})
    }
    @Test fun valuationSaveEnablesVisibleHomeRowsAndPersistsAmountsWithoutChangingCny(){
        var s=RichLedger.saveAccount(LedgerState(),Account("",0,"自制美元",10000,"USD"))
        s=RichLedger.saveAccount(s,Account("",0,"自制数字币",0,"CRYPTO",AccountType.CRYPTO));val crypto=s.accounts.last().id
        s=s.copy(settings=s.settings.copy(cashflow=s.settings.cashflow.copy(hiddenHomeCurrenciesCsv="USD,CRYPTO",showUsdOnHome=false,showCryptoOnHome=false)))
        val before=LedgerEngine.balances(s).overallMinor
        val holding=RichLedger.saveHoldings(s,listOf(CryptoHoldingEntity("BTC",100_000_000)),crypto)
        val configured=AssetValuation.configure(holding,7_300_000,mapOf("BTC" to 700_000_000_000),true,true)
        assertEquals("",configured.settings.cashflow.hiddenHomeCurrenciesCsv);assertTrue(configured.settings.cashflow.showUsdOnHome);assertTrue(configured.settings.cashflow.showCryptoOnHome)
        assertEquals(73000L,AssetValuation.usd(configured));assertEquals(70000000L,AssetValuation.crypto(configured));assertEquals(before,LedgerEngine.balances(configured).overallMinor)
        val dir=Files.createTempDirectory(File("build").toPath(),"direct-save-").toFile()
        try{val repo=LedgerRepository(AtomicLedgerStore(dir));repo.open();repo.mutate{configured};val restarted=LedgerRepository(AtomicLedgerStore(dir)).open();assertEquals(configured,restarted);assertEquals(70000000L,AssetValuation.crypto(restarted))}finally{dir.deleteRecursively()}
        val disabled=AssetValuation.configure(configured,7_300_000,configured.settings.cashflow.cryptoPriceMicros,false,false);assertEquals("CRYPTO,USD",disabled.settings.cashflow.hiddenHomeCurrenciesCsv)
    }
}
