package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.ui.withQuickEntryHistory
import com.qiyue.cashflow.shared.CoreRepeatRule
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class QuickEntryStabilityTest {
    private val day=LedgerCalendar.parse("2026-10-28")
    private fun synthetic(count:Int):LedgerState {
        val accounts=(1L..12L).map{Account("test-account-$it",it,"自制账户$it",1000000)}
        val entries=(1..count).map{n->Entry("test-entry-$n",n+12L,if(n%5==0)EntryKind.INCOME else EntryKind.EXPENSE,100L+n%900,day-n%300,600+n%600,"自制分类",accounts[n%accounts.size].id,note="自制备注$n",tags="自制标签")}
        return LedgerEngine.validate(LedgerState(nextSequence=count+13L,accounts=accounts,entries=entries))
    }
    private fun <T> stage(name:String,action:()->T):T {
        val start=System.nanoTime();val result=action();println("STABILITY_TIMING $name ${"%.3f".format((System.nanoTime()-start)/1000000.0)} ms");return result
    }
    @Test fun editingHistoryKeepsOriginalIdentityAndFundingBoundary() {
        var state=synthetic(2)
        val old=state.entries.first().copy(affectsBalance=false,affectsBudget=false,fundsDetached=true,currencyCode="USD",accountId=null,subcategory="自制子分类",tags="标签一,标签二",excludeFromBudget=true,excludeFromStatistics=true,sourceDetails="自制导入来源",importKey="a".repeat(64)+"#1",planSnapshot="自制计划快照",budgetSnapshot="自制预算快照")
        state=state.copy(entries=listOf(old)+state.entries.drop(1))
        val before=LedgerEngine.balances(state)
        val draft=EntryDraft(old.kind,999,old.epochDay,old.minuteOfDay,old.category,old.accountId,note=old.note,second=37,subcategory=old.subcategory,tags=old.tags,currencyCode=old.currencyCode,excludeFromBudget=old.excludeFromBudget,excludeFromStatistics=old.excludeFromStatistics).withQuickEntryHistory(old)
        val after=LedgerImport.saveCategorizedEntry(state,draft,day,old.id)
        val edited=after.entries.single{it.id==old.id}
        assertEquals(before,LedgerEngine.balances(after));assertEquals(old.sequence,edited.sequence)
        assertFalse(edited.affectsBalance);assertFalse(edited.affectsBudget);assertTrue(edited.fundsDetached)
        assertEquals(old.importKey,edited.importKey);assertEquals(old.sourceDetails,edited.sourceDetails)
        assertEquals(old.tags,edited.tags);assertEquals(old.subcategory,edited.subcategory);assertEquals("USD",edited.currencyCode);assertEquals(37,edited.second)
        assertEquals(old.planSnapshot,edited.planSnapshot);assertEquals(old.budgetSnapshot,edited.budgetSnapshot)
        assertEquals(after,JsonLedgerCodec().decode(JsonLedgerCodec().encode(after)))
    }
    @Test fun refundAndCurrencyDraftsKeepTheirLinks() {
        var state=LedgerImport.saveCategorizedEntry(LedgerState(),EntryDraft(EntryKind.EXPENSE,10000,day,600,"自制分类",currencyCode="USD",affectsBalance=false,affectsBudget=false),day)
        val expense=state.entries.single()
        state=LedgerImport.saveCategorizedEntry(state,EntryDraft(EntryKind.REFUND,2000,day,601,"自制分类",currencyCode="USD",refundOfId=expense.id,affectsBalance=false,affectsBudget=false),day)
        val old=state.entries.last()
        val changed=LedgerImport.saveCategorizedEntry(state,EntryDraft(old.kind,2500,old.epochDay,old.minuteOfDay,old.category,note="自制编辑退款",categoryId=old.categoryId,currencyCode=old.currencyCode,refundOfId=old.refundOfId).withQuickEntryHistory(old),day,old.id)
        assertEquals(expense.id,changed.entries.last().refundOfId);assertEquals(LedgerEngine.balances(state),LedgerEngine.balances(changed))
        val usd=LedgerImport.saveCategorizedEntry(LedgerState(),EntryDraft(EntryKind.EXPENSE,123,day,600,"自制分类",currencyCode="USD"),day)
        assertNull(usd.entries.single().accountId);assertEquals("USD",usd.entries.single().currencyCode)
    }
    @Test fun platformPublicEntryPointsStillRejectInvalidState() {
        val folder=Files.createTempDirectory(File("build").toPath(),"self-created-invalid-").toFile()
        try {
            val invalid=LedgerState(nextSequence=0)
            try{JsonLedgerCodec().encode(invalid);fail("invalid codec encode accepted")}catch(expected:IllegalArgumentException){}
            val store=AtomicLedgerStore(folder)
            try{store.save(invalid);fail("invalid store save accepted")}catch(expected:IllegalArgumentException){}
            assertFalse(store.dataFile.exists())
            val repo=LedgerRepository(store);repo.open()
            try{repo.mutate{invalid};fail("invalid mutate accepted")}catch(expected:IllegalArgumentException){}
            assertEquals(LedgerState(),repo.snapshot());assertFalse(store.dataFile.exists())
        }finally{folder.deleteRecursively()}
    }
    @Test fun representativeLinkedPhaseTiming() {
        var state=synthetic(3000)
        repeat(12){i->state=LedgerEngine.savePlan(state,PlanDraft("自制周期$i",EntryKind.EXPENSE,500,day-60,"自制分类",state.accounts.first().id,repeatRule=CoreRepeatRule.MONTHLY),today=day)}
        repeat(3){i->state=RichLedger.createDebt(state,DebtKind.BORROWED,"自制借款$i",10000,day-10,state.accounts.first().id,day+30,100,"",day)}
        stage("linked_validate"){LedgerEngine.validate(state)}
        stage("linked_home_assets_summary"){HomeAssets.calculate(state);LedgerEngine.periodSummary(state,LedgerCalendar.period(2026,10,1))}
        stage("linked_home_full_forecast_and_next_dates"){LedgerForecast.forecast(state,day,90);state.plans.forEach{RichLedger.occurrences(state,it,day+366,maxOf(it.dueEpochDay,day)).firstOrNull{!it.confirmed}}}
        val auto=state.copy(settings=state.settings.copy(cashflow=state.settings.cashflow.copy(automaticPosting=true,automaticPostingFromEpochDay=day-60)))
        val once=RichLedger.automaticPost(auto,day);assertEquals(once,RichLedger.automaticPost(once,day))
        println("STABILITY_LINKED entries=${state.entries.size} plans=${state.plans.size} debts=${state.debts.size}")
    }
    @Test fun syntheticPhaseTiming() {
        val folder=Files.createTempDirectory(File("build").toPath(),"self-created-timing-").toFile()
        try {
            val state=synthetic(3000);val codec=JsonLedgerCodec();val store=AtomicLedgerStore(folder)
            store.save(state);val raw=store.dataFile.readText()
            repeat(2){codec.decode(raw);LedgerEngine.validate(state);HomeAssets.calculate(state)}
            stage("file_read"){store.dataFile.readText()}
            stage("json_parse_only"){org.json.JSONTokener(raw).nextValue()}
            stage("codec_parse_map_validate"){codec.decode(raw)}
            stage("validate_only"){LedgerEngine.validate(state)}
            val repo=LedgerRepository(store)
            stage("repository_open"){repo.open()}
            stage("startup_automatic_pass"){repo.mutate{RichLedger.automaticPost(it,day)}}
            stage("home_balances"){LedgerEngine.balances(state)}
            stage("home_assets"){HomeAssets.calculate(state)}
            val range=LedgerCalendar.period(2026,10,1)
            stage("home_summary_and_entries"){LedgerEngine.periodSummary(state,range);state.entries.filter{it.epochDay in range}.sortedWith(compareByDescending<Entry>{it.epochDay}.thenByDescending{it.minuteOfDay}.thenByDescending{it.sequence})}
            stage("home_forecast"){LedgerForecast.forecast(state,day,90)}
            stage("save_durable"){repo.mutate{LedgerImport.saveCategorizedEntry(it,EntryDraft(EntryKind.EXPENSE,123,day,600,"自制分类"),day)}}
            println("STABILITY_DATA entries=3000 bytes=${raw.toByteArray().size} self_created=true")
        } finally {folder.deleteRecursively()}
    }
}
