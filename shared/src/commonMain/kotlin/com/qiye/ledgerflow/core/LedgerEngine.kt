package com.qiye.ledgerflow.core

import com.qiyue.cashflow.shared.CoreRepeatRule

/** Immutable operations. The repository commits to durable storage before publishing any result. */
object LedgerEngine {
    fun validate(state: LedgerState): LedgerState {
        require(state.debtArchives.size<=100000&&state.debtArchives.all{it.contentStamp.length<=80&&it.debtIds.isNotEmpty()&&it.debtIds.size<=100000&&it.entryIds.size<=100000&&it.debtIds.distinct().size==it.debtIds.size&&it.entryIds.distinct().size==it.entryIds.size}){"借贷归档无效"}
        val archives=state.debtArchives.filter{a->a.debtIds.all{id->state.debts.any{it.id==id}}&&a.entryIds.all{id->state.entries.any{it.id==id}}}
        if(archives!=state.debtArchives)return validate(state.copy(debtArchives=archives))
        require(state.nextSequence in 1 until Long.MAX_VALUE) { "数据序号无效" }
        require(state.settings.monthStartDay in 1..31) { "账期起始日无效" }
        val all = state.accounts.size + state.entries.size + state.plans.size + state.corrections.size + state.categories.size + state.debts.size + state.budgets.size
        require(all <= 100_000 && state.importedKeys.size <= 100_000) { "数据条目过多" }
        val ids = state.accounts.map { it.id } + state.entries.map { it.id } + state.plans.map { it.id } + state.corrections.map { it.id } + state.categories.map { it.id } + state.debts.map { it.id }
        require(ids.all { it.isNotBlank() && it.length <= 100 } && ids.toSet().size == ids.size) { "数据包含重复或无效编号" }
        val sequences = state.accounts.map { it.sequence } + state.entries.map { it.sequence } + state.plans.map { it.sequence } + state.corrections.map { it.sequence } + state.categories.map { it.sequence } + state.debts.map { it.sequence } + state.budgets.map { it.id }
        require(sequences.all { it in 1 until state.nextSequence } && sequences.toSet().size == sequences.size) { "数据动作序号无效或重复" }
        require(state.cashflowSources.size<=100 && state.cashflowSources.map{it.key}.distinct().size==state.cashflowSources.size) { "迁入来源重复或过多" }
        state.cashflowSources.forEach { require(it.key.matches(Regex("[a-f0-9]{64}")) && it.originalJson.length<=64*1024*1024) { "现金流迁入来源无效" }; LedgerCalendar.validateDay(it.importedEpochDay) }
        require(state.fundingBoundary in 0 until state.nextSequence) { "资金边界无效" }
        require(state.moduleImportKeys.distinct().size==state.moduleImportKeys.size && state.moduleImportKeys.all{it.matches(Regex("(BOOK|FLOW):[a-f0-9]{64}"))}) { "模块来源无效" }
        state.settledOccurrences.forEach{a->require(state.plans.any{it.id==a.planId});LedgerCalendar.validateDay(a.originalEpochDay);LedgerCalendar.validateDay(a.actualEpochDay);require(a.amountMinor>0);Money.checked(a.amountMinor)}
        require(state.settledOccurrences.map{it.planId to it.originalEpochDay}.distinct().size==state.settledOccurrences.size)
        state.frozenBudgetSpends.forEach{a->require(state.budgets.any{it.id==a.budgetId}&&a.capturedSequence in 1 until state.nextSequence);Money.checked(a.amountMinor);LedgerCalendar.validateDay(a.cycleStart)}
        val accountIds = state.accounts.map { it.id }.toSet()
        fun account(id: String?) { require(id == null || id in accountIds) { "账户不存在" } }
        fun currency(code: String) { require(code.matches(Regex("[A-Z]{2,8}"))) { "币种无效" } }
        state.accounts.forEach { require(it.name.isNotBlank() && it.name.length <= 60) { "账户名称无效" }; Money.checked(it.openingMinor); require(it.exchangeRateToCnyMicros>=0); currency(it.currencyCode) }
        require(state.accounts.map { it.name }.toSet().size == state.accounts.size) { "账户名称重复" }
        val categories = state.categories.associateBy { it.id }
        state.categories.forEach { c ->
            require(c.name.isNotBlank() && c.name.length <= 80 && c.kind in listOf(EntryKind.EXPENSE, EntryKind.INCOME)) { "分类无效" }
            c.parentId?.let { parent -> require(categories[parent]?.let { it.parentId == null && it.kind == c.kind } == true && parent != c.id) { "分类仅支持父子两级且类型须一致" } }
        }
        require(state.categories.map { Triple(it.kind, it.parentId, it.name) }.toSet().size == state.categories.size) { "同级分类名称重复" }
        state.entries.forEach {
            require(it.amountMinor > 0) { "记账金额须大于 0" }; Money.checked(it.amountMinor)
            LedgerCalendar.validateDay(it.epochDay); require(it.minuteOfDay in 0..1439 && it.second in 0..59) { "时间无效" }
            account(it.accountId); account(it.targetAccountId); currency(it.currencyCode)
            require(it.category.isNotBlank() && it.category.length <= 80 && it.note.length <= 4000 && it.tags.length <= 2000 && it.sourceDetails.length <= 8000 && it.debtCounterparty.length<=100) { "分类、备注、标签或来源说明过长" }
            require(it.categoryId == null || it.categoryId in categories) { "分类关联无效" }
            it.accountId?.let { id -> val code = state.accounts.first { a -> a.id == id }.currencyCode; require(code == it.currencyCode || code == "CRYPTO" && CurrencyUnits.isCrypto(it.currencyCode)) { "账目与账户币种不一致" } }
            if (it.kind == EntryKind.TRANSFER) {
                require(it.accountId != null && it.targetAccountId != null && it.accountId != it.targetAccountId) { "转账须选择两个不同账户" }
                require(state.accounts.first { a -> a.id == it.accountId }.currencyCode == state.accounts.first { a -> a.id == it.targetAccountId }.currencyCode) { "转账仅支持相同法币币种" }
            } else require(it.targetAccountId == null) { "收支不应有转入账户" }
            require(it.planId == null || state.plans.any { p -> p.id == it.planId }) { "计划关联无效" }
            require(it.debtId == null || state.debts.any { d -> d.id == it.debtId && d.currencyCode == it.currencyCode }) { "债务关联无效" }
            require(it.budgetId == null || state.budgets.any { b -> b.id == it.budgetId }) { "预算关联无效" }
            it.refundOfId?.let { id -> require(it.kind == EntryKind.REFUND && state.entries.any { e -> e.id == id && e.kind == EntryKind.EXPENSE && e.currencyCode == it.currencyCode }) { "退款原账目无效" } }
        }
        val linked = state.entries.filter { it.planId != null }.map { it.planId to (it.occurrenceEpochDay ?: state.plans.first { p -> p.id == it.planId }.dueEpochDay) }
        require(linked.toSet().size == linked.size) { "同一计划期次有重复实际记录" }
        state.entries.filter { it.refundOfId != null }.groupBy { it.refundOfId }.forEach { (id, refunds) ->
            require(refunds.fold(0L) { s, e -> Money.add(s, e.amountMinor) } <= state.entries.first { it.id == id }.amountMinor) { "累计退款/报销超过原支出金额" }
        }
        state.plans.forEach {
            require(it.kind != EntryKind.TRANSFER && it.kind != EntryKind.REFUND) { "计划类型无效" }
            require(it.title.isNotBlank() && it.title.length <= 100 && it.category.isNotBlank() && it.category.length <= 80 && it.note.length <= 4000) { "计划名称、分类或备注无效" }
            require(it.amountMinor > 0) { "计划金额须大于 0" }; Money.checked(it.amountMinor); currency(it.currencyCode)
            LedgerCalendar.validateDay(it.dueEpochDay); it.endEpochDay?.let { end -> LedgerCalendar.validateDay(end); require(end >= it.dueEpochDay) { "结束日期早于开始日期" } }; account(it.accountId)
            require(it.installmentTotal in 1..1200 && it.installmentRemaining in 0..it.installmentTotal) { "分期期数无效" }
            Money.checked(it.installmentTotalAmountMinor); require(it.installmentTotalAmountMinor >= 0) { "分期总额无效" }
            if (it.installmentTotal > 1) require(it.repeatRule != CoreRepeatRule.NONE && (it.installmentTotalAmountMinor == 0L || it.installmentTotalAmountMinor >= it.installmentTotal)) { "分期须有有效周期；总额可为0表示按每期金额，否则须足够分配" }
            require(it.categoryId == null || it.categoryId in categories) { "计划分类关联无效" }; it.postingFromEpochDay?.let(LedgerCalendar::validateDay); it.notBeforeEpochDay?.let(LedgerCalendar::validateDay); it.fundingCapMinor?.let{cap->require(cap>0);Money.checked(cap)}; require(it.budgetId == null || state.budgets.any { b -> b.id == it.budgetId }) { "计划预算关联无效" }
            require(it.debtId == null || state.debts.any { d -> d.id == it.debtId && d.currencyCode == it.currencyCode }) { "债务计划关联无效" }
        }
        state.corrections.forEach { account(it.accountId); Money.checked(it.beforeMinor); Money.checked(it.afterMinor); LedgerCalendar.validateDay(it.epochDay); require(it.minuteOfDay in 0..1439 && it.reason.isNotBlank() && it.reason.length <= 200) { "校正记录无效" } }
        state.debts.forEach { d ->
            require(d.person.isNotBlank() && d.person.length <= 100 && d.principalMinor > 0 && d.interestMinor >= 0 && d.note.length <= 4000) { "债务信息无效" }
            Money.checked(d.principalMinor); Money.checked(d.interestMinor); LedgerCalendar.validateDay(d.openedEpochDay); d.dueEpochDay?.let(LedgerCalendar::validateDay); account(d.accountId); currency(d.currencyCode)
            require(state.entries.count { it.debtId == d.id && it.kind == if (d.kind == DebtKind.BORROWED) EntryKind.BORROW else EntryKind.LEND } == 1) { "债务须关联一笔建立本金记录" }
            require(state.entries.first { it.debtId==d.id && it.kind==if(d.kind==DebtKind.BORROWED)EntryKind.BORROW else EntryKind.LEND }.amountMinor==d.principalMinor) { "建立本金与债务本金不一致" }; require(RichLedger.outstanding(state, d.id) in 0..d.principalMinor) { "债务还款超出未结本金" }
        }
        state.budgets.forEach { b -> require(b.monthStartDay==null||b.monthStartDay in 1..31); require(b.name.isNotBlank() && b.totalCents >= 0 && b.remainingCents >= 0 && b.baselineSequence in 0 until state.nextSequence) { "预算金额或基准无效" }; Money.checked(b.totalCents); Money.checked(b.remainingCents); LedgerCalendar.validateDay(b.startDateEpochDay); LedgerCalendar.validateDay(b.endDateEpochDay); require(b.endDateEpochDay >= b.startDateEpochDay && (b.chargeDay == null || b.chargeDay in 1..31)) { "预算周期或扣款日无效" } }
        require(state.holdings.map { it.accountId to it.symbol }.distinct().size == state.holdings.size) { "数字货币持仓重复" }
        state.holdings.forEach { account(it.accountId); require(CurrencyUnits.isCrypto(it.symbol) && it.amountUnits >= 0 && it.asOfSequence in 0 until state.nextSequence) { "数字货币持仓无效" }; Money.checked(it.amountUnits) }
        state.occurrenceOverrides.forEach { o -> require(state.plans.any { it.id == o.planId }) { "延期关联无效" }; LedgerCalendar.validateDay(o.originalEpochDay); LedgerCalendar.validateDay(o.dueEpochDay) }
        require(state.occurrenceOverrides.map { it.planId to it.originalEpochDay }.distinct().size == state.occurrenceOverrides.size) { "延期记录重复" }
        require(state.importedKeys.all { it.matches(Regex("[a-f0-9]{64}#[1-9][0-9]*")) } && state.importedKeys.distinct().size == state.importedKeys.size) { "导入身份无效或重复" }
        val cf = state.settings.cashflow
        require(cf.defaultHorizonDays in 1..366 && cf.budgetMonthStartDay in 1..31 && cf.usdRateMicros in 1..1_000_000_000) { "预测设置无效" }
        require(cf.cryptoPriceMicros.keys.all{it in AssetValuation.symbols}&&cf.cryptoPriceMicros.values.all{it in 0..Money.LIMIT}){"币价无效"}
        cf.automaticPostingFromEpochDay?.let(LedgerCalendar::validateDay)
        balances(state); RichLedger.cryptoBalances(state)
        return state
    }

    fun balances(state: LedgerState): Balances {
        var overall = 0L
        val accounts = state.accounts.associate { it.id to it.openingMinor }.toMutableMap()
        val entries = state.entries.associateBy { it.sequence }; val corrections = state.corrections.associateBy { it.sequence }
        (entries.keys + corrections.keys).sorted().forEach { sequence ->
            entries[sequence]?.takeIf { it.affectsBalance && !it.fundsDetached && sequence>state.fundingBoundary }?.let { e ->
                val delta = if (e.kind.isOutflow()) -e.amountMinor else e.amountMinor
                if (e.kind != EntryKind.TRANSFER && e.currencyCode == "CNY") overall = Money.add(overall, delta)
                if (!CurrencyUnits.isCrypto(e.currencyCode)) {
                    e.accountId?.let { accounts[it] = Money.add(accounts.getValue(it), delta) }
                    if (e.kind == EntryKind.TRANSFER) e.targetAccountId?.let { accounts[it] = Money.add(accounts.getValue(it), e.amountMinor) }
                }
            }
            corrections[sequence]?.takeIf{sequence>state.fundingBoundary}?.let { c -> if (c.accountId == null) overall = c.afterMinor else accounts[c.accountId] = c.afterMinor }
        }
        return Balances(overall, accounts.toMap())
    }

    private fun oldAccountAllowed(state:LedgerState,id:String?,accountId:String?):Boolean = accountId==null || state.accounts.none{it.id==accountId&&it.archived} || id!=null&&state.entries.any{it.id==id&&(it.accountId==accountId||it.targetAccountId==accountId)}

    fun addAccount(state: LedgerState, name: String, opening: Long): LedgerState {
        val seq = state.nextSequence
        return validate(state.copy(nextSequence = seq + 1, accounts = state.accounts + Account("account-$seq", seq, name.trim(), opening)))
    }

    fun saveEntry(state: LedgerState, draft: EntryDraft, today: Long, id: String? = null): LedgerState {
        require((!draft.affectsBalance&&!draft.affectsBudget)||(oldAccountAllowed(state,id,draft.accountId) && oldAccountAllowed(state,id,draft.targetAccountId))){"历史账户已归档，请选择当前账户或无账户"}
        require(draft.epochDay <= today) { "未来收支请使用计划；实际日期不能晚于今天" }
        val old = id?.let { key -> state.entries.firstOrNull { it.id == key } ?: error("账目不存在") }
        if(draft.categoryId!=null && (old==null || old.categoryId!=draft.categoryId) && draft.affectsBalance) {
            val chosen=state.categories.firstOrNull { it.id==draft.categoryId }?:error("分类不存在")
            require(chosen.active && (chosen.parentId==null || state.categories.first { it.id==chosen.parentId }.active)){"分类已停用，请先启用或选择其他分类"}
        }
        val seq = old?.sequence ?: state.nextSequence
        val currency = draft.accountId?.let { key -> state.accounts.firstOrNull { it.id == key }?.currencyCode?.takeUnless { it == "CRYPTO" } } ?: draft.currencyCode
        val assignedBudget = draft.budgetId ?: old?.budgetId ?: draft.refundOfId?.let { id -> state.entries.firstOrNull { it.id == id }?.budgetId } ?: state.budgets.filter { it.isActive && BudgetRules.cycle(it,draft.epochDay) != null }.singleOrNull()?.id
        val entry = Entry(old?.id ?: "entry-$seq", seq, draft.kind, draft.amountMinor, draft.epochDay, draft.minuteOfDay,
            draft.category.trim(), draft.accountId, draft.targetAccountId, draft.note.trim(), old?.planId, if (old == null) draft.affectsBalance else old.affectsBalance,
            draft.second, draft.categoryId, draft.subcategory.trim(), draft.tags.trim(), currency, draft.excludeFromBudget,
            draft.excludeFromStatistics, old?.affectsBudget ?: draft.affectsBudget, draft.debtId ?: old?.debtId, draft.isInterest,
            draft.refundOfId, old?.occurrenceEpochDay, old?.importKey, draft.sourceDetails.ifBlank { old?.sourceDetails.orEmpty() }, assignedBudget, old?.settlementId ?: draft.settlementId,old?.fundsDetached?:false,old?.planSnapshot.orEmpty(),old?.budgetSnapshot.orEmpty(),old?.debtCounterparty.orEmpty())
        val plans = if (old?.planId == null) state.plans else state.plans.map { p ->
            if (p.id == old.planId && p.repeatRule == CoreRepeatRule.NONE && p.debtId == null) p.copy(kind = entry.kind, amountMinor = entry.amountMinor, category = entry.category, accountId = entry.accountId, currencyCode = entry.currencyCode) else p
        }
        return validate(state.copy(nextSequence = if (old == null) seq + 1 else state.nextSequence, entries = state.entries.filterNot { it.id == id } + entry, plans = plans))
    }

    fun deleteEntry(state: LedgerState, id: String): LedgerState {
        val entry = state.entries.firstOrNull { it.id == id } ?: error("账目不存在")
        require(entry.kind !in listOf(EntryKind.BORROW, EntryKind.LEND) || entry.debtId == null) { "建立本金请在债务管理中删除整笔债务" }
        require(state.entries.none { it.refundOfId == id }) { "请先删除关联退款/报销" }
        return validate(state.copy(entries = state.entries.filterNot { it.id == id }))
    }

    fun correctBalance(state: LedgerState, accountId: String?, after: Long, day: Long, minute: Int, reason: String): LedgerState {
        val balances = balances(state); val before = if (accountId == null) balances.overallMinor else balances.accountsMinor[accountId] ?: error("账户不存在")
        val seq = state.nextSequence
        return validate(state.copy(nextSequence = seq + 1, corrections = state.corrections + BalanceCorrection("correction-$seq", seq, accountId, before, after, day, minute, reason.trim())))
    }

    /** User account calibration shifts CNY overall by the same delta; import/anchors use correctBalance. */
    fun calibrateAccount(state:LedgerState,accountId:String?,after:Long,day:Long,minute:Int,reason:String):LedgerState {
        if(accountId==null)return correctBalance(state,null,after,day,minute,reason)
        val a=state.accounts.firstOrNull{it.id==accountId}?:error("账户不存在")
        val before=balances(state)
        val delta=Money.add(after,-before.accountsMinor.getValue(accountId))
        if(delta==0L)return state
        val next=correctBalance(state,accountId,after,day,minute,reason)
        return if(a.currencyCode=="CNY")correctBalance(next,null,Money.add(before.overallMinor,delta),day,minute,"账户差额同步：${a.name}")else next
    }

    fun savePlan(state: LedgerState, draft: PlanDraft, id: String? = null, today: Long? = null): LedgerState {
        val old = id?.let { key -> state.plans.firstOrNull { it.id == key } ?: error("计划不存在") }
        val seq = old?.sequence ?: state.nextSequence
        val currency = draft.accountId?.let { key -> state.accounts.first { it.id == key }.currencyCode.takeUnless { it == "CRYPTO" } } ?: draft.currencyCode
        val plan = Plan(old?.id ?: "plan-$seq", seq, draft.title.trim(), draft.kind, draft.amountMinor, draft.dueEpochDay, draft.category.trim(), draft.accountId, draft.note.trim(), currency, draft.repeatRule, draft.endEpochDay, draft.installmentTotal, draft.installmentRemaining, draft.installmentTotalAmountMinor, draft.active, draft.temporary, draft.usesBudget, draft.categoryId, draft.subcategory, draft.debtId, draft.isInterest, draft.budgetId, old?.postingFromEpochDay ?: draft.postingFromEpochDay ?: today?.takeIf { draft.repeatRule != CoreRepeatRule.NONE }?.let { maxOf(it,draft.dueEpochDay) }, old?.notBeforeEpochDay,old?.fundingCapMinor)
        return validate(state.copy(nextSequence = if (old == null) seq + 1 else state.nextSequence, plans = state.plans.filterNot { it.id == id } + plan))
    }

    fun postponePlan(state: LedgerState, id: String, day: Long): LedgerState {
        val p = state.plans.firstOrNull { it.id == id } ?: error("计划不存在")
        require(day > p.dueEpochDay) { "延期日期须晚于原计划日期" }
        return RichLedger.postponeOccurrence(state, id, p.dueEpochDay, day)
    }
    fun deletePlan(state: LedgerState, id: String): LedgerState {
        require(state.plans.any { it.id == id }) { "计划不存在" }; require(state.entries.none { it.planId == id } && state.settledOccurrences.none{it.planId==id}) { "已有兑现记录，请停用计划" }
        return validate(state.copy(plans = state.plans.filterNot { it.id == id }, occurrenceOverrides = state.occurrenceOverrides.filterNot { it.planId == id }))
    }
    fun confirmPlan(state: LedgerState, id: String, day: Long, minute: Int, today: Long): LedgerState {
        val p = state.plans.firstOrNull { it.id == id } ?: error("计划不存在")
        return RichLedger.confirmOccurrence(state, id, p.dueEpochDay, day, minute, today)
    }
    fun undoPlan(state: LedgerState, id: String): LedgerState {
        require(state.plans.any { it.id == id }) { "计划不存在" }
        return validate(state.copy(entries = state.entries.filterNot { it.planId == id }))
    }
    fun periodSummary(state: LedgerState, range: LongRange): PeriodSummary {
        val selected = state.entries.filter { it.epochDay in range && !it.excludeFromStatistics && it.currencyCode == "CNY" }
        fun sum(kind: EntryKind) = selected.filter { it.kind == kind }.fold(0L) { s, e -> Money.add(s, e.amountMinor) }
        return PeriodSummary(sum(EntryKind.INCOME), Money.add(sum(EntryKind.EXPENSE), -sum(EntryKind.REFUND)))
    }
}

fun EntryKind.isOutflow() = this in listOf(EntryKind.EXPENSE, EntryKind.TRANSFER, EntryKind.LEND, EntryKind.REPAY)
fun EntryKind.isOrdinary() = this in listOf(EntryKind.EXPENSE, EntryKind.INCOME, EntryKind.REFUND)

object CurrencyUnits {
    fun isCrypto(code: String) = code in listOf("USDT", "BTC", "LTC")
    fun digits(code: String) = if (isCrypto(code)) 8 else if (code in listOf("JPY", "KRW", "VND")) 0 else 2
    fun parse(text: String, code: String, signed: Boolean = false): Long {
        val d = digits(code); val v = text.trim(); val pattern = (if (signed) "-?" else "") + "\\d{1,16}" + if (d > 0) "(\\.\\d{1,$d})?" else ""
        require(v.matches(Regex(pattern))) { "金额格式无效，$code 最多${d}位小数" }
        val p = v.removePrefix("-").split('.'); val scale = pow10(d)
        val whole = p[0].toLongOrNull() ?: error("金额过大"); require(whole <= Money.LIMIT / scale) { "金额超出范围" }
        val units = Money.add(whole * scale, if (d == 0) 0 else (p.getOrNull(1) ?: "").padEnd(d, '0').toLong())
        return if (v.startsWith('-')) -units else units
    }
    fun format(amount: Long, code: String): String {
        Money.checked(amount); val d = digits(code); val scale = pow10(d); val a = if (amount < 0) -amount else amount
        return (if (amount < 0) "-" else "") + (a / scale) + if (d == 0) "" else "." + (a % scale).toString().padStart(d, '0')
    }
    private fun pow10(digits: Int): Long { var n = 1L; repeat(digits) { n *= 10 }; return n }
}
