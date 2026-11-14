package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.ui.billNote
import com.qiye.ledgerflow.ui.defaultExpenseCategory
import com.qiyue.cashflow.shared.CoreRepeatRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExpenseDefaultsAndPlanBillsTest {
    private val day = LedgerCalendar.parse("2026-11-07")

    @Test fun confirmingImportedRentCreatesOneActualEntryWithThePlanNameAndCategory() {
        val rent = Plan("plan-1", 1, "房租", EntryKind.EXPENSE, 1000, day, "居住",
            note = RichLedger.CASHFLOW_IMPORT_NOTE, repeatRule = CoreRepeatRule.MONTHLY, subcategory = "租赁")
        val before = LedgerState(nextSequence = 2, plans = listOf(rent))
        val after = RichLedger.confirmOccurrence(before, rent.id, day, day, 720, day)
        val entry = after.entries.single()
        assertEquals("房租", entry.note)
        assertEquals("居住", entry.category)
        assertEquals("租赁", entry.subcategory)
        assertEquals(RichLedger.CASHFLOW_IMPORT_NOTE, entry.sourceDetails)
        assertEquals("房租", entry.copy(note = RichLedger.CASHFLOW_IMPORT_NOTE).billNote(after))
        assertEquals(after, RichLedger.confirmOccurrence(after, rent.id, day, day, 720, day))

        val longNote = "注".repeat(4000)
        val custom = before.copy(plans = listOf(rent.copy(note = longNote)))
        val customActual = RichLedger.confirmOccurrence(custom, rent.id, day, day, 720, day)
        assertEquals(longNote, customActual.entries.single().note)
        assertEquals("房租 · $longNote", customActual.entries.single().billNote(customActual))
    }

    @Test fun expenseDefaultsRoundTripAndIgnoreAnInactiveParent() {
        val food = LedgerCategory("cat-1", 1, "食物", EntryKind.EXPENSE)
        val lunch = LedgerCategory("cat-2", 2, "午餐", EntryKind.EXPENSE, parentId = food.id)
        val settings = LedgerSettings(defaultExpenseCategoryId = lunch.id, defaultExpenseExcludesBudget = true)
        val state = LedgerState(nextSequence = 3, categories = listOf(food, lunch), settings = settings)
        val codec = JsonLedgerCodec()
        assertEquals(settings, codec.decode(codec.encode(state)).settings)
        assertEquals(lunch, state.defaultExpenseCategory())
        assertNull(state.copy(categories = listOf(food.copy(active = false), lunch)).defaultExpenseCategory())

        val old = JSONObject(codec.encode(state))
        old.getJSONObject("settings").apply {
            remove("defaultExpenseCategoryId")
            remove("defaultExpenseExcludesBudget")
        }
        val decoded = codec.decode(old.toString())
        assertNull(decoded.defaultExpenseCategory())
        assertFalse(decoded.settings.defaultExpenseExcludesBudget)
    }
}
