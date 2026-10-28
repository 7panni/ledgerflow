package com.qiye.ledgerflow.core

import kotlin.test.*

class LedgerEngineTest {
    private val today = LedgerCalendar.parse("2026-10-21")
    private fun initial(minor: Long = 100_000) = LedgerEngine.correctBalance(LedgerState(), null, minor, today, 600, "自制测试初始余额")
    private fun expense(state: LedgerState, minor: Long, account: String? = null) = LedgerEngine.saveEntry(state, EntryDraft(EntryKind.EXPENSE, minor, today, 601, "自制支出", account), today)
    private fun addPlan(state: LedgerState, minor: Long = 20_000, day: Long = today + 1) = LedgerEngine.savePlan(state, PlanDraft("自制未来支出", EntryKind.EXPENSE, minor, day, "测试分类"))

    @Test fun mixedAccountsAndInternalTransfersKeepOverallIndependent() {
        var state = LedgerEngine.addAccount(initial(), "测试账户甲", 50_000)
        state = LedgerEngine.addAccount(state, "测试账户乙", 20_000)
        val a = state.accounts[0].id; val b = state.accounts[1].id
        assertEquals(100_000, LedgerEngine.balances(state).overallMinor)
        state = expense(state, 2_000)
        assertEquals(98_000, LedgerEngine.balances(state).overallMinor)
        assertEquals(50_000, LedgerEngine.balances(state).accountsMinor[a])
        state = expense(state, 3_000, a)
        state = LedgerEngine.saveEntry(state, EntryDraft(EntryKind.INCOME, 5_000, today, 700, "自制收入", b), today)
        state = LedgerEngine.saveEntry(state, EntryDraft(EntryKind.TRANSFER, 10_000, today, 800, "内部转账", a, b), today)
        val balance = LedgerEngine.balances(state)
        assertEquals(100_000, balance.overallMinor)
        assertEquals(37_000, balance.accountsMinor[a])
        assertEquals(35_000, balance.accountsMinor[b])
        val totals = LedgerEngine.periodSummary(state, today..today)
        assertEquals(5_000, totals.incomeMinor); assertEquals(5_000, totals.expenseMinor)
    }

    @Test fun correctionAnchorsSurviveEditsAndDeletesOfEarlierEntries() {
        var state = LedgerEngine.addAccount(initial(), "甲", 50_000)
        val account = state.accounts.single().id
        state = expense(state, 10_000, account)
        val oldId = state.entries.single().id
        state = LedgerEngine.correctBalance(state, null, 200_000, today, 900, "重新核对")
        state = LedgerEngine.saveEntry(state, EntryDraft(EntryKind.EXPENSE, 30_000, today - 1, 300, "更正历史", account), today, oldId)
        assertEquals(200_000, LedgerEngine.balances(state).overallMinor)
        assertEquals(20_000, LedgerEngine.balances(state).accountsMinor[account])
        state = LedgerEngine.deleteEntry(state, oldId)
        assertEquals(200_000, LedgerEngine.balances(state).overallMinor)
        assertEquals(50_000, LedgerEngine.balances(state).accountsMinor[account])
        state = expense(state, 5_000)
        assertEquals(195_000, LedgerEngine.balances(state).overallMinor)
        assertEquals(5_000, LedgerEngine.periodSummary(state, today..today).expenseMinor)
        assertEquals(195_000, LedgerForecast.forecast(state, today, 30).endCnyMinor)
    }

    @Test fun accountCorrectionDoesNotChangeOverallOrStatistics() {
        var state = LedgerEngine.addAccount(initial(), "甲", 5_000)
        state = LedgerEngine.correctBalance(state, state.accounts.single().id, -2_000, today, 600, "账户核对")
        assertEquals(100_000, LedgerEngine.balances(state).overallMinor)
        assertEquals(-2_000, LedgerEngine.balances(state).accountsMinor.values.single())
        assertEquals(0, LedgerEngine.periodSummary(state, today..today).netMinor)
    }

    @Test fun planConfirmationIsIdempotentAndUndoRestoresPrediction() {
        var state = addPlan(initial())
        val plan = state.plans.single()
        assertEquals(100_000, LedgerEngine.balances(state).overallMinor)
        assertEquals(80_000, LedgerForecast.forecast(state, today, 30).endCnyMinor)
        state = LedgerEngine.confirmPlan(state, plan.id, today, 600, today)
        assertEquals(state, LedgerEngine.confirmPlan(state, plan.id, today, 601, today))
        assertEquals(1, state.entries.size)
        assertEquals(80_000, LedgerEngine.balances(state).overallMinor)
        assertEquals(80_000, LedgerForecast.forecast(state, today, 30).endCnyMinor)
        val actual = state.entries.single()
        state = LedgerEngine.saveEntry(state, EntryDraft(EntryKind.EXPENSE, 25_000, today, 601, "更改后的分类"), today, actual.id)
        assertEquals(25_000, state.plans.single().amountMinor)
        assertEquals(75_000, LedgerEngine.balances(state).overallMinor)
        state = LedgerEngine.undoPlan(state, plan.id)
        assertEquals(state, LedgerEngine.undoPlan(state, plan.id))
        assertEquals(100_000, LedgerEngine.balances(state).overallMinor)
        assertEquals(75_000, LedgerForecast.forecast(state, today, 30).endCnyMinor)
    }

    @Test fun confirmedPlanCannotBeEditedOrDeletedUntilUndo() {
        var state = addPlan(initial())
        val plan = state.plans.single()
        state = LedgerEngine.confirmPlan(state, plan.id, today, 600, today)
        assertFails { LedgerEngine.postponePlan(state, plan.id, today + 5) }
        assertFails { LedgerEngine.deletePlan(state, plan.id) }
        state = LedgerEngine.deleteEntry(state, state.entries.single().id)
        assertEquals(80_000, LedgerForecast.forecast(state, today, 30).endCnyMinor)
    }

    @Test fun postponeMovesOnlyPredictedDateAndOverdueIsTodayEstimate() {
        var state = addPlan(initial(), day = today - 3)
        assertEquals(80_000, LedgerForecast.forecast(state, today, 2).days.first().cnyBalanceMinor)
        state = LedgerEngine.postponePlan(state, state.plans.single().id, today + 5)
        val prediction = LedgerForecast.forecast(state, today, 7)
        assertEquals(100_000, prediction.days[4].cnyBalanceMinor)
        assertEquals(80_000, prediction.days[5].cnyBalanceMinor)
        assertEquals(100_000, LedgerEngine.balances(state).overallMinor)
        assertFails { LedgerEngine.postponePlan(state, state.plans.single().id, today + 5) }
    }

    @Test fun moneyRejectsRoundingScientificNotationAndOverflow() {
        assertEquals(1_234, Money.parse("12.34"))
        assertEquals(-1, Money.parse("-0.01", true))
        listOf("1.234", "1e3", "1,234", "-1", "", "NaN", "99999999999999").forEach { assertFails { Money.parse(it) } }
        assertFails { Money.add(Money.LIMIT, 1) }
        assertFails { expense(initial(-Money.LIMIT), 1) }
        assertFails { LedgerForecast.forecast(addPlan(initial(-Money.LIMIT), 1), today, 30) }
    }

    @Test fun periodStartClampsIndependentlyAndJanuaryBelongsToPriorPeriod() {
        val feb = LedgerCalendar.period(2024, 2, 31)
        assertEquals("2024-02-29", LedgerCalendar.format(feb.first))
        assertEquals("2024-03-30", LedgerCalendar.format(feb.last))
        val jan = LedgerCalendar.periodMonth(LedgerCalendar.parse("2026-01-03"), 10)
        assertEquals(2025, jan.year); assertEquals(12, jan.month)
        val ten = LedgerCalendar.period(2026, 9, 10)
        assertEquals("2026-09-10", LedgerCalendar.format(ten.first)); assertEquals("2026-10-09", LedgerCalendar.format(ten.last))
        assertFails { LedgerCalendar.parse("2026-02-29") }
    }

    @Test fun transferAndInvalidReferencesAreRejectedWithoutMutatingInput() {
        val state = initial()
        assertFails { LedgerEngine.saveEntry(state, EntryDraft(EntryKind.TRANSFER, 1, today, 100, "转账"), today) }
        assertFails { expense(state, 1, "missing") }
        assertFails { LedgerEngine.saveEntry(state, EntryDraft(EntryKind.INCOME, 1, today + 1, 100, "未来"), today) }
        assertEquals(100_000, LedgerEngine.balances(state).overallMinor)
    }
}
