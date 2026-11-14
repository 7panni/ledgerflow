package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*
import org.junit.Assert.*
import org.junit.Test

/** Self-created accounts only; no device or real ledger input. */
class HomeAssetsAccountSelectionTest {
    private val day=LedgerCalendar.parse("2026-10-29")
    private fun initial()=LedgerEngine.addAccount(LedgerState(),"自制可见人民币账户",50000)
    @Test fun visibleAccountExpenseReducesAccountAssetsAndOverall() {
        val before=initial();val account=before.accounts.single().id
        val after=LedgerImport.saveCategorizedEntry(before,EntryDraft(EntryKind.EXPENSE,1234,day,600,"自制支出",account),day)
        assertEquals(50000L,HomeAssets.calculate(before).totalCnyMinor)
        assertEquals(48766L,HomeAssets.calculate(after).totalCnyMinor)
        assertEquals(48766L,LedgerEngine.balances(after).accountsMinor.getValue(account))
        assertEquals(-1234L,LedgerEngine.balances(after).overallMinor)
    }
    @Test fun noAccountExpenseOnlyChangesIndependentOverall() {
        val before=initial()
        val after=LedgerImport.saveCategorizedEntry(before,EntryDraft(EntryKind.EXPENSE,1234,day,600,"自制无账户支出"),day)
        assertNull(after.entries.single().accountId)
        assertEquals(50000L,HomeAssets.calculate(after).totalCnyMinor)
        assertEquals(LedgerEngine.balances(before).accountsMinor,LedgerEngine.balances(after).accountsMinor)
        assertEquals(-1234L,LedgerEngine.balances(after).overallMinor)
    }
    @Test fun successfulSavePublishesFreshImmutableSnapshotForAssets() {
        val store=object:LedgerStore {
            var saved=initial()
            override fun load()=saved
            override fun save(state:LedgerState){saved=state}
            override fun saveBeforeRestore(){}
        }
        val repository=LedgerRepository(store);val before=repository.open()
        val after=repository.mutate{LedgerImport.saveCategorizedEntry(it,EntryDraft(EntryKind.EXPENSE,1234,day,600,"自制新快照",it.accounts.single().id),day)}
        assertNotSame(before,after);assertSame(after,repository.snapshot());assertSame(after,store.saved)
        assertEquals(50000L,HomeAssets.calculate(before).totalCnyMinor)
        assertEquals(48766L,HomeAssets.calculate(repository.snapshot()).totalCnyMinor)
    }
}
