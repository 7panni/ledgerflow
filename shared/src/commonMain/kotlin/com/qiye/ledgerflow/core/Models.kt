package com.qiye.ledgerflow.core

import com.qiye.ledgerflow.cashflow.data.*
import com.qiyue.cashflow.shared.CoreRepeatRule

enum class EntryKind { EXPENSE, INCOME, TRANSFER, REFUND, BORROW, LEND, REPAY, COLLECT }
enum class DebtKind { BORROWED, LENT }

data class Account(val id: String, val sequence: Long, val name: String, val openingMinor: Long,
    val currencyCode: String = "CNY", val accountType: AccountType = AccountType.CUSTOM,
    val active: Boolean = true, val includeInForecast: Boolean = true, val exchangeRateToCnyMicros: Long = 1_000_000, val archived:Boolean=false)

data class Entry(
    val id: String, val sequence: Long, val kind: EntryKind, val amountMinor: Long,
    val epochDay: Long, val minuteOfDay: Int, val category: String,
    val accountId: String? = null, val targetAccountId: String? = null,
    val note: String = "", val planId: String? = null, val affectsBalance: Boolean = true,
    val second: Int = 0, val categoryId: String? = null, val subcategory: String = "", val tags: String = "",
    val currencyCode: String = "CNY", val excludeFromBudget: Boolean = false,
    val excludeFromStatistics: Boolean = false, val affectsBudget: Boolean = true,
    val debtId: String? = null, val isInterest: Boolean = false, val refundOfId: String? = null,
    val occurrenceEpochDay: Long? = null, val importKey: String? = null,
    val sourceDetails: String = "", val budgetId: Long? = null, val settlementId: String? = null,
    val fundsDetached:Boolean=false, val planSnapshot:String="", val budgetSnapshot:String="", val debtCounterparty:String="",
)

data class Plan(
    val id: String, val sequence: Long, val title: String, val kind: EntryKind,
    val amountMinor: Long, val dueEpochDay: Long, val category: String,
    val accountId: String? = null, val note: String = "",
    val currencyCode: String = "CNY", val repeatRule: CoreRepeatRule = CoreRepeatRule.NONE,
    val endEpochDay: Long? = null, val installmentTotal: Int = 1,
    val installmentRemaining: Int = 0, val installmentTotalAmountMinor: Long = 0,
    val active: Boolean = true, val temporary: Boolean = false, val usesBudget: Boolean = false,
    val categoryId: String? = null, val subcategory: String = "", val debtId: String? = null,
    val isInterest: Boolean = false, val budgetId: Long? = null, val postingFromEpochDay: Long? = null,
    val notBeforeEpochDay: Long? = null, val fundingCapMinor:Long?=null,
)

data class OccurrenceOverride(val planId: String, val originalEpochDay: Long, val dueEpochDay: Long)

data class BalanceCorrection(val id: String, val sequence: Long, val accountId: String?,
    val beforeMinor: Long, val afterMinor: Long, val epochDay: Long, val minuteOfDay: Int, val reason: String)

data class LedgerCategory(val id: String, val sequence: Long, val name: String, val kind: EntryKind,
    val parentId: String? = null, val order: Int = 0, val active: Boolean = true)

data class Debt(val id: String, val sequence: Long, val kind: DebtKind, val person: String,
    val principalMinor: Long, val openedEpochDay: Long, val accountId: String? = null,
    val currencyCode: String = "CNY", val dueEpochDay: Long? = null, val interestMinor: Long = 0,
    val note: String = "", val interestProvided: Boolean = true)

data class CashflowSource(val key:String, val importedEpochDay:Long, val accountIds:Map<Long,String>,
    val planIds:Map<Long,String>, val budgetIds:Map<Long,Long>, val originalJson:String)

data class SettledOccurrence(val planId:String,val originalEpochDay:Long,val actualEpochDay:Long,val amountMinor:Long)
data class FrozenBudgetSpend(val budgetId:Long,val cycleStart:Long,val amountMinor:Long,val capturedSequence:Long)

data class LedgerSettings(val monthStartDay: Int = 1,
    val cashflow: AppSettingsEntity = AppSettingsEntity(onboardingCompleted = true, seeded = true))

data class DebtArchive(val debtIds:List<String>,val entryIds:List<String>,val contentStamp:String="")

data class LedgerState(
    val nextSequence: Long = 1, val accounts: List<Account> = emptyList(),
    val entries: List<Entry> = emptyList(), val plans: List<Plan> = emptyList(),
    val corrections: List<BalanceCorrection> = emptyList(), val settings: LedgerSettings = LedgerSettings(),
    val categories: List<LedgerCategory> = emptyList(), val debts: List<Debt> = emptyList(),
    val budgets: List<BudgetEntity> = emptyList(), val holdings: List<CryptoHoldingEntity> = emptyList(),
    val occurrenceOverrides: List<OccurrenceOverride> = emptyList(), val importedKeys: List<String> = emptyList(),
    val cashflowSources: List<CashflowSource> = emptyList(),
    val settledOccurrences:List<SettledOccurrence> = emptyList(), val frozenBudgetSpends:List<FrozenBudgetSpend> = emptyList(),
    val fundingBoundary:Long=0, val moduleImportKeys:List<String> = emptyList(), val debtArchives:List<DebtArchive> = emptyList(),
)

data class EntryDraft(val kind: EntryKind, val amountMinor: Long, val epochDay: Long,
    val minuteOfDay: Int, val category: String, val accountId: String? = null,
    val targetAccountId: String? = null, val note: String = "", val second: Int = 0,
    val categoryId: String? = null, val subcategory: String = "", val tags: String = "",
    val currencyCode: String = "CNY", val excludeFromBudget: Boolean = false,
    val excludeFromStatistics: Boolean = false, val affectsBalance: Boolean = true,
    val affectsBudget: Boolean = true, val debtId: String? = null, val isInterest: Boolean = false,
    val refundOfId: String? = null, val sourceDetails: String = "", val budgetId: Long? = null, val settlementId: String? = null)

data class PlanDraft(val title: String, val kind: EntryKind, val amountMinor: Long,
    val dueEpochDay: Long, val category: String, val accountId: String? = null, val note: String = "",
    val currencyCode: String = "CNY", val repeatRule: CoreRepeatRule = CoreRepeatRule.NONE,
    val endEpochDay: Long? = null, val installmentTotal: Int = 1, val installmentRemaining: Int = 0,
    val installmentTotalAmountMinor: Long = 0, val active: Boolean = true, val temporary: Boolean = false,
    val usesBudget: Boolean = false, val categoryId: String? = null, val subcategory: String = "",
    val debtId: String? = null, val isInterest: Boolean = false, val budgetId: Long? = null, val postingFromEpochDay: Long? = null)

data class PlanOccurrence(val plan: Plan, val originalEpochDay: Long, val dueEpochDay: Long,
    val amountMinor: Long, val actual: Entry?,val settled:Boolean=false) { val confirmed:Boolean get()=actual!=null||settled; val key: String get() = "${plan.id}@$originalEpochDay" }

data class Balances(val overallMinor: Long, val accountsMinor: Map<String, Long>)
data class PeriodSummary(val incomeMinor: Long, val expenseMinor: Long) {
    val netMinor: Long get() = Money.add(incomeMinor, -expenseMinor)
}

data class CategoryStat(val categoryId: String?, val label: String, val amountMinor: Long, val count: Int)

interface LedgerCodec { fun encode(state: LedgerState): String; fun decode(text: String): LedgerState }
interface LedgerStore {
    fun load(): LedgerState?
    fun save(state: LedgerState)
    fun saveBeforeRestore()
}
