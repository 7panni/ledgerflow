package com.qiye.ledgerflow.cashflow.data


enum class EntryType { EXPENSE, INCOME, DEBT, TRANSFER, REFUND }
enum class AccountType { CASH, WECHAT, ALIPAY, CMB, PAYPAL, CRYPTO, CUSTOM }
enum class RepeatRule { NONE, DAILY, WEEKLY, MONTHLY, QUARTERLY, SEMIANNUAL, YEARLY }
enum class BudgetPeriod { WEEKLY, MONTHLY, CUSTOM }
enum class BudgetMethod { DAILY_AVERAGE, SPECIFIC_DAY }
enum class AdjustmentType { BALANCE_CALIBRATION, TEMPORARY }

data class AccountEntity(
    val id: Long = 0,
    val name: String,
    val initialBalanceCents: Long = 0,
    val currentBalanceCents: Long = initialBalanceCents,
    val currencyCode: String = "CNY",
    val exchangeRateToCnyMicros: Long = 1_000_000,
    val includeInForecast: Boolean = true,
    val accountType: AccountType = AccountType.CUSTOM,
    val isActive: Boolean = true
)

data class CategoryEntity(
    val id: Long = 0,
    val name: String,
    val parentName: String? = null,
    val type: EntryType
)

data class TransactionEntity(
    val id: Long = 0,
    val occurredAtMillis: Long,
    val type: EntryType,
    val amountCents: Long,
    val currencyCode: String = "CNY",
    val exchangeRateToCnyMicros: Long = 1_000_000,
    val accountName: String,
    val toAccountName: String? = null,
    val category: String,
    val subcategory: String? = null,
    val note: String? = null,
    val tags: String? = null,
    val counterparty: String? = null,
    val feeCents: Long = 0,
    val refundAmountCents: Long = 0,
    val refundAtMillis: Long? = null,
    val installmentTotal: Int = 1,
    val installmentIndex: Int = 1,
    val liabilityHidden: Boolean = false,
    val excludeFromBudget: Boolean = false,
    val excludeFromCashflow: Boolean = false,
    val sourceApp: String = "好好记账",
    val sourceRowHash: String
)

data class PlannedTransactionEntity(
    val id: Long = 0,
    val name: String,
    val type: EntryType,
    val amountCents: Long,
    val currencyCode: String = "CNY",
    val exchangeRateToCnyMicros: Long = 1_000_000,
    val accountName: String,
    val toAccountName: String? = null,
    val startDateEpochDay: Long,
    val repeatRule: RepeatRule = RepeatRule.NONE,
    val endDateEpochDay: Long? = null,
    val installmentTotal: Int = 1,
    val installmentRemaining: Int = 0,
    val installmentTotalAmountCents: Long = 0,
    /** 已自动计入真实余额的日期范围；该日期及以前的计划不会再次入账。 */
    val balanceAppliedThroughEpochDay: Long? = null,
    val isActive: Boolean = true,
    val usesBudget: Boolean = false,
    val budgetId: Long? = null
)

data class BudgetEntity(
    val id: Long = 0,
    val name: String,
    val totalCents: Long,
    val remainingCents: Long,
    val period: BudgetPeriod,
    val startDateEpochDay: Long,
    val endDateEpochDay: Long,
    val accountName: String,
    val method: BudgetMethod = BudgetMethod.DAILY_AVERAGE,
    val chargeDay: Int? = null,
    val carryOver: Boolean = false,
    val isActive: Boolean = true,
    val baselineSequence: Long = 0,
    val monthStartDay: Int? = null
)

data class AdjustmentEntity(
    val id: Long = 0,
    val name: String,
    val type: AdjustmentType,
    val amountCents: Long,
    val accountName: String,
    val dateEpochDay: Long,
    val isIncome: Boolean = false,
    val usesBudget: Boolean = false,
    val appliedToBalance: Boolean = false,
    val budgetId: Long? = null
)

data class CryptoHoldingEntity(
    val symbol: String,
    val amountUnits: Long = 0,
    val asOfSequence: Long = 0,
    val accountId: String? = null
)

data class AppSettingsEntity(
    val id: Int = 1,
    val usdRateMicros: Long = 7_200_000,
    val defaultHorizonDays: Int = 90,
    val budgetMonthStartDay: Int = 1,
    val showUsdOnHome: Boolean = true,
    val showCryptoOnHome: Boolean = true,
    val showPaypalOnAccounts: Boolean = true,
    val showAlipayOnAccounts: Boolean = true,
    val showCryptoOnAccounts: Boolean = true,
    val appMode: String = "SIMPLE",
    val customModulesCsv: String = "",
    val hiddenAccountIdsCsv: String = "",
    val hiddenHomeCurrenciesCsv: String = "",
    val showFinanceTips: Boolean = true,
    val financeTipChangesDaily: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val seeded: Boolean = false,
    val automaticPosting: Boolean = false,
    val automaticPostingFromEpochDay: Long? = null,
    val showUsdValuation:Boolean=false,
    val showCryptoValuation:Boolean=false,
    val cryptoPriceMicros:Map<String,Long> = emptyMap()
)
