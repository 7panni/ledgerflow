package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.ui.deleteActualEntry
import com.qiye.ledgerflow.ui.entryCanDelete
import org.junit.Assert.*
import org.junit.Test

class EntryActionsTest {
    private val day=LedgerCalendar.parse("2026-10-28")
    private fun rejected(action:()->Unit){try{action();fail("Expected rejection")}catch(expected:IllegalArgumentException){}}
    @Test fun historicalDeletionKeepsCurrentFunding() {
        val state=LedgerImport.saveCategorizedEntry(LedgerState(),EntryDraft(EntryKind.EXPENSE,1234,day-10,600,"自制历史",affectsBalance=false,affectsBudget=false),day)
        val before=LedgerEngine.balances(state)
        val after=deleteActualEntry(state,state.entries.single().id)
        assertTrue(after.entries.isEmpty());assertEquals(before,LedgerEngine.balances(after))
    }
    @Test fun principalCannotBeDeletedThroughRowAction() {
        val state=RichLedger.createDebt(LedgerState(),DebtKind.BORROWED,"自制本金",10000,day,null,day+10,100,"",day)
        val opening=state.entries.single()
        assertFalse(entryCanDelete(opening));rejected{deleteActualEntry(state,opening.id)}
        assertEquals(10000L,RichLedger.outstanding(state,state.debts.single().id))
    }
    @Test fun settlementRowUsesExistingCombinedUndo() {
        var state=RichLedger.createDebt(LedgerState(),DebtKind.BORROWED,"自制本息",10000,day-10,null,day+10,100,"",day)
        val debt=state.debts.single()
        state=RichLedger.settleDebt(state,debt.id,4000,40,day,600,null,day,"self-created-settlement")
        val interest=state.entries.single{it.isInterest}
        val expected=RichLedger.undoSettlement(state,interest.settlementId!!)
        val after=deleteActualEntry(state,interest.id)
        assertEquals(expected,after);assertEquals(10000L,RichLedger.outstanding(after,debt.id))
        assertTrue(after.entries.none{it.settlementId==interest.settlementId})
    }
    @Test fun planRowDeleteRestoresExistingPendingOccurrence() {
        var state=LedgerEngine.savePlan(LedgerState(),PlanDraft("自制计划",EntryKind.EXPENSE,500,day,"自制计划"))
        val plan=state.plans.single()
        state=LedgerEngine.confirmPlan(state,plan.id,day,600,day)
        val entry=state.entries.single()
        val after=deleteActualEntry(state,entry.id)
        assertEquals(LedgerEngine.deleteEntry(state,entry.id),after)
        assertTrue(RichLedger.pending(after,day).any{it.plan.id==plan.id})
    }
    @Test fun expenseWithRefundStillRequiresRefundRemovalFirst() {
        var state=LedgerImport.saveCategorizedEntry(LedgerState(),EntryDraft(EntryKind.EXPENSE,1000,day,600,"自制支出"),day)
        val expense=state.entries.single()
        state=LedgerImport.saveCategorizedEntry(state,EntryDraft(EntryKind.REFUND,100,day,601,"自制支出",refundOfId=expense.id),day)
        rejected{deleteActualEntry(state,expense.id)}
        val refund=state.entries.last()
        assertEquals(LedgerEngine.deleteEntry(state,refund.id),deleteActualEntry(state,refund.id))
    }
}
