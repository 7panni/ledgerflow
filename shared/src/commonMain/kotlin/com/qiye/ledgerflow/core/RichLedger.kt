package com.qiye.ledgerflow.core

import com.qiye.ledgerflow.cashflow.data.*
import com.qiyue.cashflow.shared.*

object RichLedger {
    const val CASHFLOW_IMPORT_NOTE = "从现金流备份迁入"
    private val calendar = CoreForecastEngine()
    fun asCore(p: Plan) = CorePlan(p.title,
        if (p.kind.isOutflow()) CoreEntryType.EXPENSE else CoreEntryType.INCOME,
        p.amountMinor, p.currencyCode, p.accountId ?: "overall", startEpochDay = p.dueEpochDay,
        repeatRule = p.repeatRule, endEpochDay = p.endEpochDay, installmentTotal = p.installmentTotal,
        installmentRemaining = p.installmentRemaining, installmentTotalAmountMinor = p.installmentTotalAmountMinor, balanceAppliedThroughEpochDay = p.postingFromEpochDay?.minus(1), active = p.active)
    fun occurs(p: Plan, day: Long) = calendar.occursOn(asCore(p), day)
    fun occurrenceAmount(p: Plan, day: Long) = minOf(calendar.installmentAmountOn(asCore(p), day),p.fundingCapMinor?:Money.LIMIT)
    fun actual(state: LedgerState, p: Plan, day: Long) = state.entries.firstOrNull { it.planId == p.id && (it.occurrenceEpochDay ?: p.dueEpochDay) == day }
    fun confirmed(state:LedgerState,p:Plan,day:Long)=actual(state,p,day)!=null||state.settledOccurrences.any{it.planId==p.id&&it.originalEpochDay==day}
    fun occurrences(state: LedgerState, p: Plan, through: Long, from: Long = p.dueEpochDay): List<PlanOccurrence> {
        if (through < from) return emptyList()
        val overrides = state.occurrenceOverrides.filter { it.planId == p.id }.associateBy { it.originalEpochDay }
        return (maxOf(p.dueEpochDay, from)..minOf(through, p.endEpochDay ?: through)).mapNotNull { day ->
            if (!occurs(p, day)) null else PlanOccurrence(p, day, overrides[day]?.dueEpochDay ?: day, occurrenceAmount(p, day), actual(state, p, day),state.settledOccurrences.any{it.planId==p.id&&it.originalEpochDay==day})
        }
    }
    fun pending(state: LedgerState, through: Long): List<PlanOccurrence> = state.plans.filter { it.active }.flatMap { p ->
        // Include an occurrence originally after the window only when its override moves it into it.
        val last = maxOf(through, state.occurrenceOverrides.filter { it.planId == p.id && it.dueEpochDay <= through }.maxOfOrNull { it.originalEpochDay } ?: through)
        occurrences(state, p, last).filter { !it.confirmed && it.dueEpochDay <= through && (p.notBeforeEpochDay==null || it.dueEpochDay>=p.notBeforeEpochDay) && (p.debtId == null || if(p.isInterest) interestOutstanding(state,p.debtId)>0 else outstanding(state,p.debtId)>0) }
    }.sortedWith(compareBy<PlanOccurrence> { it.dueEpochDay }.thenBy { it.plan.sequence }.thenBy { it.originalEpochDay })

    fun confirmOccurrence(state: LedgerState, id: String, original: Long, day: Long, minute: Int, today: Long): LedgerState {
        val p = state.plans.firstOrNull { it.id == id } ?: error("计划不存在")
        if (confirmed(state, p, original)) return state
        require(occurs(p, original)) { "不是此计划的有效期次" }
        val planned = occurrenceAmount(p, original)
        val amount = if(p.debtId != null) minOf(planned,if(p.isInterest) interestOutstanding(state,p.debtId) else outstanding(state,p.debtId)) else planned
        require(amount > 0) { "此债务本金已结清" }
        val result = LedgerEngine.saveEntry(state, EntryDraft(p.kind, amount, day, minute, p.category, p.accountId, note = p.note.takeUnless { it.isBlank() || it == CASHFLOW_IMPORT_NOTE } ?: p.title,
            sourceDetails = p.note.takeIf { it == CASHFLOW_IMPORT_NOTE }.orEmpty(),
            categoryId = p.categoryId, subcategory = p.subcategory, currencyCode = p.currencyCode,
            debtId = p.debtId, isInterest = p.isInterest, budgetId = p.budgetId, affectsBudget = p.kind.isOrdinary() && p.usesBudget), today)
        return LedgerEngine.validate(result.copy(entries = result.entries.map { e -> if (e.sequence == state.nextSequence) e.copy(planId = p.id, occurrenceEpochDay = original) else e }))
    }
    fun undoOccurrence(state: LedgerState, id: String, original: Long): LedgerState { require(state.settledOccurrences.none{it.planId==id&&it.originalEpochDay==original}){"账目已清除，资金已冻结；此兑现凭据不回滚，请明确新建或调整未来计划"}; return LedgerEngine.validate(state.copy(entries = state.entries.filterNot { it.planId == id && it.occurrenceEpochDay == original })) }
    fun postponeOccurrence(state: LedgerState, id: String, original: Long, day: Long): LedgerState {
        val p = state.plans.firstOrNull { it.id == id } ?: error("计划不存在")
        require(occurs(p, original) && !confirmed(state, p, original)) { "仅可延期未发生的有效期次" }
        require(day > (state.occurrenceOverrides.firstOrNull { it.planId==id && it.originalEpochDay==original }?.dueEpochDay ?: original)) { "延期须晚于当前预计发生日" }
        return LedgerEngine.validate(state.copy(occurrenceOverrides = state.occurrenceOverrides.filterNot { it.planId == id && it.originalEpochDay == original } + OccurrenceOverride(id, original, day)))
    }
    fun automaticPost(state: LedgerState, today: Long): LedgerState {
        val settings = state.settings.cashflow
        if (!settings.automaticPosting) return state
        val start = settings.automaticPostingFromEpochDay ?: today
        var next = state
        pending(state, today).filter { it.dueEpochDay >= start && !it.plan.temporary }.forEach { o ->
            next = confirmOccurrence(next, o.plan.id, o.originalEpochDay, o.dueEpochDay, 0, today)
        }
        return next
    }

    fun saveAccount(state: LedgerState, item: Account): LedgerState {
        val old = state.accounts.firstOrNull { it.id == item.id }
        if (old != null) {
            require(old.currencyCode == item.currencyCode || state.entries.none { it.accountId == old.id || it.targetAccountId == old.id } && state.corrections.none { it.accountId == old.id } && state.plans.none { it.accountId == old.id }) { "已有记录的账户不可改币种，请另建账户" }
            return LedgerEngine.validate(state.copy(accounts = state.accounts.map { if (it.id == old.id) item.copy(sequence = old.sequence, openingMinor = old.openingMinor, name = item.name.trim()) else it }))
        }
        val seq = state.nextSequence
        return LedgerEngine.validate(state.copy(nextSequence = seq + 1, accounts = state.accounts + item.copy(id = "account-$seq", sequence = seq, name = item.name.trim())))
    }
    fun cryptoAccountBalances(state: LedgerState, accountId: String?, symbol: String): Long {
        val primary = state.accounts.firstOrNull { it.currencyCode == "CRYPTO" }?.id
        val base = state.holdings.firstOrNull { it.symbol == symbol && (it.accountId ?: primary) == accountId }
        return state.entries.filter { it.currencyCode == symbol && it.affectsBalance && !it.fundsDetached && it.sequence>state.fundingBoundary && it.sequence > (base?.asOfSequence ?: 0) }.fold(base?.amountUnits ?: 0L) { amount,e ->
            var value=amount
            if(e.accountId==accountId) value=Money.add(value,if(e.kind.isOutflow())-e.amountMinor else e.amountMinor)
            if(e.kind==EntryKind.TRANSFER && e.targetAccountId==accountId) value=Money.add(value,e.amountMinor)
            value
        }
    }
    fun cryptoBalances(state: LedgerState): Map<String, Long> = listOf("USDT","BTC","LTC").associateWith { symbol ->
        val accounts=state.accounts.filter { it.currencyCode=="CRYPTO"&&!it.archived }.map { it.id }
        if(accounts.isEmpty()) state.holdings.filter { it.symbol==symbol&&it.accountId==null&&state.accounts.none{a->a.currencyCode=="CRYPTO"} }.fold(0L) { a,h->Money.add(a,h.amountUnits) }
        else accounts.fold(0L) { a,id->Money.add(a,cryptoAccountBalances(state,id,symbol)) }
    }
    fun saveHoldings(state: LedgerState, items: List<CryptoHoldingEntity>, accountId: String? = null): LedgerState {
        val id=accountId ?: state.accounts.firstOrNull { it.currencyCode=="CRYPTO" }?.id
        return LedgerEngine.validate(state.copy(holdings=state.holdings.filterNot { (it.accountId ?: state.accounts.firstOrNull { a->a.currencyCode=="CRYPTO" }?.id)==id }+items.map { it.copy(asOfSequence=state.nextSequence-1,accountId=id) }))
    }

    fun categoryKind(kind: EntryKind) = if (kind == EntryKind.INCOME) EntryKind.INCOME else EntryKind.EXPENSE
    fun categoryLabels(state: LedgerState, id: String?): Pair<String, String>? {
        val c = state.categories.firstOrNull { it.id == id } ?: return null
        return if (c.parentId == null) c.name to "" else (state.categories.first { it.id == c.parentId }.name to c.name)
    }
    fun saveCategory(state: LedgerState, name: String, kind: EntryKind, parentId: String? = null, id: String? = null, active: Boolean = true): LedgerState {
        val old = id?.let { key -> state.categories.firstOrNull { it.id == key } ?: error("分类不存在") }
        if (old != null && (old.kind != kind || old.parentId != parentId)) {
            require(state.entries.none { it.categoryId == old.id } && state.plans.none { it.categoryId == old.id } && state.categories.none { it.parentId == old.id }) { "在用分类不可变更类型/父级，请新建并停用旧分类" }
        }
        val seq = old?.sequence ?: state.nextSequence
        val item = LedgerCategory(old?.id ?: "category-$seq", seq, name.trim(), kind, parentId, old?.order ?: (state.categories.filter { it.parentId == parentId && it.kind == kind }.maxOfOrNull { it.order } ?: -1) + 1, active)
        val updated = state.copy(nextSequence = if (old == null) seq + 1 else state.nextSequence, categories = state.categories.filterNot { it.id == id } + item)
        return LedgerEngine.validate(updated.copy(entries = updated.entries.map { e -> categoryLabels(updated, e.categoryId)?.let { (a,b) -> e.copy(category = a, subcategory = b) } ?: e }, plans = updated.plans.map { p -> categoryLabels(updated, p.categoryId)?.let { (a,b) -> p.copy(category = a, subcategory = b) } ?: p }))
    }
    fun moveCategory(state: LedgerState, id: String, delta: Int): LedgerState {
        val c = state.categories.first { it.id == id }; val siblings = state.categories.filter { it.parentId == c.parentId && it.kind == c.kind }.sortedWith(compareBy<LedgerCategory> { it.order }.thenBy { it.sequence }).toMutableList()
        val i = siblings.indexOfFirst { it.id == id }; val j = i + delta; if (j !in siblings.indices) return state
        val other = siblings[j]; siblings[j] = c; siblings[i] = other
        val positions = siblings.mapIndexed { index, value -> value.id to index }.toMap()
        return LedgerEngine.validate(state.copy(categories = state.categories.map { v -> positions[v.id]?.let { v.copy(order = it) } ?: v }))
    }
    fun deleteCategory(state: LedgerState, id: String): LedgerState {
        require(state.entries.none { it.categoryId == id } && state.plans.none { it.categoryId == id } && state.categories.none { it.parentId == id }) { "此分类仍在使用或有子分类，请停用以保留旧账关系" }
        return LedgerEngine.validate(state.copy(categories = state.categories.filterNot { it.id == id }))
    }
    /** Idempotent v1 upgrade. Text labels and account/balance actions keep their original meaning. */
    fun upgradeV1(state: LedgerState): LedgerState {
        var next = state
        val names = state.entries.filter { it.kind.isOrdinary() }.map { categoryKind(it.kind) to it.category } + state.plans.filter { it.kind.isOrdinary() }.map { categoryKind(it.kind) to it.category }
        names.distinct().forEach { (kind, name) -> if (next.categories.none { it.kind == kind && it.name == name && it.parentId == null }) next = saveCategory(next, name, kind) }
        return LedgerEngine.validate(next.copy(entries = next.entries.map { e -> e.copy(categoryId = e.categoryId ?: next.categories.firstOrNull { it.kind == categoryKind(e.kind) && it.name == e.category && it.parentId == null }?.id, occurrenceEpochDay = e.occurrenceEpochDay ?: e.planId?.let { key -> next.plans.first { it.id == key }.dueEpochDay }) }, plans = next.plans.map { p -> p.copy(categoryId = p.categoryId ?: next.categories.firstOrNull { it.kind == categoryKind(p.kind) && it.name == p.category && it.parentId == null }?.id) }))
    }

    fun outstanding(state:LedgerState,debtId:String):Long=DebtGroups.remaining(state,debtId)
    fun createDebt(state: LedgerState, kind: DebtKind, person: String, principal: Long, day: Long, accountId: String?, due: Long?, interest: Long, note: String, today: Long, interestProvided:Boolean=true): LedgerState {
        val seq = state.nextSequence; val currency = accountId?.let { key -> state.accounts.first { it.id == key }.currencyCode } ?: "CNY"
        require(currency != "CRYPTO" && !CurrencyUnits.isCrypto(currency)) { "债务本金仅支持法币" }
        val d = Debt("debt-$seq", seq, kind, person.trim(), principal, day, accountId, currency, due, interest, note.trim(),interestProvided)
        var next = state.copy(nextSequence = seq + 1, debts = state.debts + d)
        next = LedgerEngine.saveEntry(next, EntryDraft(if (kind == DebtKind.BORROWED) EntryKind.BORROW else EntryKind.LEND, principal, day, 0, if (kind == DebtKind.BORROWED) "借入本金" else "借出本金", accountId, note = note, currencyCode = currency, debtId = d.id, affectsBudget = false), today)
        if (due != null) {
            next = LedgerEngine.savePlan(next, PlanDraft("${d.person} · ${if (kind == DebtKind.BORROWED) "归还" else "收回"}本金", if (kind == DebtKind.BORROWED) EntryKind.REPAY else EntryKind.COLLECT, principal, due, "债务本金", accountId, currencyCode = currency, debtId = d.id))
            if (interest > 0) next = LedgerEngine.savePlan(next, PlanDraft("${d.person} · 利息", if (kind == DebtKind.BORROWED) EntryKind.EXPENSE else EntryKind.INCOME, interest, due, "利息", accountId, currencyCode = currency, debtId = d.id, isInterest = true))
        }
        return LedgerEngine.validate(next)
    }
    fun updateDebt(state:LedgerState,id:String,person:String,due:Long?,interest:Long,note:String,interestProvided:Boolean=true):LedgerState {
        val d=state.debts.first { it.id==id };var next=state.copy(debts=state.debts.map { if(it.id==id)it.copy(person=person.trim(),dueEpochDay=due,interestMinor=interest,note=note.trim(),interestProvided=interestProvided) else it })
        val oldPlans=next.plans.filter { it.debtId==id }
        val mutable=oldPlans.filter { p->next.entries.none { it.planId==p.id } }.map { it.id }.toSet()
        next=next.copy(plans=next.plans.filterNot { it.id in mutable },occurrenceOverrides=next.occurrenceOverrides.filterNot { it.planId in mutable })
        if(due!=null){
            val principal=outstanding(next,id);val interestLeft=interestOutstanding(next,id)
            if(principal>0)next=LedgerEngine.savePlan(next,PlanDraft("${person} · 本金",if(d.kind==DebtKind.BORROWED)EntryKind.REPAY else EntryKind.COLLECT,principal,due,"债务本金",d.accountId,currencyCode=d.currencyCode,debtId=id))
            if(interestLeft>0)next=LedgerEngine.savePlan(next,PlanDraft("${person} · 利息",if(d.kind==DebtKind.BORROWED)EntryKind.EXPENSE else EntryKind.INCOME,interestLeft,due,"利息",d.accountId,currencyCode=d.currencyCode,debtId=id,isInterest=true))
        }
        return LedgerEngine.validate(next)
    }
    fun interestOutstanding(state: LedgerState, debtId: String): Long {
        val d=state.debts.first { it.id==debtId }
        val paid=state.entries.filter { it.debtId==debtId && it.isInterest }.fold(0L) { total,e->Money.add(total,e.amountMinor) }
        return Money.add(d.interestMinor,-paid).coerceAtLeast(0)
    }
    fun settleDebt(state: LedgerState, debtId: String, principal: Long, interest: Long, day: Long, minute: Int, accountId: String?, today: Long, requestId: String, budgetId: Long? = null): LedgerState {
        if (state.entries.any { it.settlementId == requestId }) return state
        val d = state.debts.first { it.id == debtId }
        require(principal in 1..outstanding(state, debtId) && interest >= 0) { "本金须大于0且不超过未结金额，利息不能为负" }
        val direction = if (d.kind == DebtKind.BORROWED) EntryKind.REPAY else EntryKind.COLLECT
        var next = LedgerEngine.saveEntry(state, EntryDraft(direction, principal, day, minute, "债务本金", accountId, note = d.person, currencyCode = d.currencyCode, debtId = d.id, affectsBudget = false, settlementId = requestId), today)
        if (interest > 0) next = LedgerEngine.saveEntry(next, EntryDraft(if (d.kind == DebtKind.BORROWED) EntryKind.EXPENSE else EntryKind.INCOME, interest, day, minute, "利息", accountId, note = d.person, currencyCode = d.currencyCode, debtId = d.id, isInterest = true, affectsBudget = budgetId!=null, budgetId = budgetId, settlementId = requestId), today)
        return LedgerEngine.validate(next)
    }
    fun undoSettlement(state: LedgerState, requestId: String): LedgerState = LedgerEngine.validate(state.copy(entries = state.entries.filterNot { it.settlementId == requestId }))
    fun deleteDebt(state: LedgerState, id: String): LedgerState {
        require(outstanding(state,id)==state.debts.first{it.id==id}.principalMinor && state.entries.none { it.debtId == id && it.kind in listOf(EntryKind.REPAY, EntryKind.COLLECT) } ) { "请先撤销此债务的还款/收款" }
        val planIds = state.plans.filter { it.debtId == id }.map { it.id }.toSet()
        return LedgerEngine.validate(state.copy(debts = state.debts.filterNot { it.id == id }, entries = state.entries.filterNot { it.debtId == id }, plans = state.plans.filterNot { it.debtId == id }, occurrenceOverrides = state.occurrenceOverrides.filterNot { it.planId in planIds }))
    }
    fun saveBudget(state: LedgerState, budget: BudgetEntity, recalibrateRemaining: Boolean = true, asOfDay: Long = budget.startDateEpochDay): LedgerState {
        val old=state.budgets.firstOrNull { it.id==budget.id }; val id=old?.id ?: state.nextSequence
        val currentCycle=if(recalibrateRemaining && budget.period!=BudgetPeriod.CUSTOM) BudgetRules.cycle(budget,asOfDay) else null
        val next=budget.copy(id=id,remainingCents=if(old!=null && !recalibrateRemaining) old.remainingCents else budget.remainingCents,
            baselineSequence=if(old!=null && !recalibrateRemaining)old.baselineSequence else state.nextSequence-1,
            startDateEpochDay=if(old!=null&&!recalibrateRemaining&&old.period==budget.period)old.startDateEpochDay else currentCycle?.first ?: budget.startDateEpochDay,endDateEpochDay=if(old!=null&&!recalibrateRemaining&&old.period==budget.period)old.endDateEpochDay else currentCycle?.second ?: budget.endDateEpochDay)
        return LedgerEngine.validate(state.copy(nextSequence=if(old==null)state.nextSequence+1 else state.nextSequence,budgets=state.budgets.filterNot { it.id==id }+next))
    }
    fun deleteBudget(state: LedgerState, id: Long): LedgerState {
        require(state.entries.none { it.budgetId == id }) { "已有账目指定此预算，请停用以保留关系" }
        require(state.plans.none{it.budgetId==id}) { "仍有计划指定此预算，请先调整计划" }; return LedgerEngine.validate(state.copy(budgets = state.budgets.filterNot { it.id == id },frozenBudgetSpends=state.frozenBudgetSpends.filterNot{it.budgetId==id}))
    }
}

/** Cycle calculation adapted from the user's CoreForecastEngine; actual spend is applied once. */
object BudgetRules {
    fun monthlyStartDay(b:BudgetEntity):Int=b.monthStartDay?:CivilDate.fromEpochDay(b.startDateEpochDay).day
    fun cycle(b: BudgetEntity, day: Long): Pair<Long,Long>? {
        if (day < b.startDateEpochDay) return null
        if (b.period == BudgetPeriod.CUSTOM) return if (day <= b.endDateEpochDay) b.startDateEpochDay to b.endDateEpochDay else null
        if (b.period == BudgetPeriod.WEEKLY) { val start = b.startDateEpochDay + ((day - b.startDateEpochDay) / 7) * 7; return start to start + 6 }
        if(b.monthStartDay!=null){val m=LedgerCalendar.periodMonth(day,b.monthStartDay);val range=LedgerCalendar.period(m.year,m.month,b.monthStartDay);return maxOf(range.first,b.startDateEpochDay) to range.last}
        val initial = CivilDate.fromEpochDay(b.startDateEpochDay); val current = CivilDate.fromEpochDay(day)
        val months = (current.year-initial.year)*12 + current.month-initial.month
        var start = initial.plusMonths(months)
        if (start.toEpochDay() > day) start = initial.plusMonths(months-1)
        val index = (start.year-initial.year)*12 + start.month-initial.month
        return start.toEpochDay() to initial.plusMonths(index+1).toEpochDay()-1
    }
    fun unifyMonthStart(state:LedgerState,dayOfMonth:Int,today:Long):LedgerState {
        require(dayOfMonth in 1..31){"请输入1—31"}
        val marker=state.nextSequence
        val frozen=state.frozenBudgetSpends.toMutableList()
        val budgets=state.budgets.map{b->if(b.period!=BudgetPeriod.MONTHLY)b else {
            val oldCycle=cycle(b,today)
            if(oldCycle==null)b.copy(monthStartDay=dayOfMonth)else {
                val used=spent(state,b,oldCycle);val left=remaining(state,b,today)
                val m=LedgerCalendar.periodMonth(today,dayOfMonth);val range=LedgerCalendar.period(m.year,m.month,dayOfMonth)
                frozen.removeAll{it.budgetId==b.id}
                if(used!=0L)frozen+=FrozenBudgetSpend(b.id,range.first,used,marker)
                b.copy(startDateEpochDay=range.first,endDateEpochDay=range.last,remainingCents=Money.add(left,used),baselineSequence=marker-1,monthStartDay=dayOfMonth)
            }
        }}
        return LedgerEngine.validate(state.copy(nextSequence=marker+1,budgets=budgets,frozenBudgetSpends=frozen,settings=state.settings.copy(monthStartDay=dayOfMonth,cashflow=state.settings.cashflow.copy(budgetMonthStartDay=dayOfMonth))))
    }
    private fun assigned(state: LedgerState, e: Entry): Long? = e.budgetId
    fun spent(state: LedgerState, b: BudgetEntity, range: Pair<Long,Long>): Long = state.entries.filter { e ->
        e.sequence > b.baselineSequence && !e.fundsDetached && e.affectsBudget && !e.excludeFromBudget && e.currencyCode == "CNY" && e.epochDay in range.first..range.second && e.kind in listOf(EntryKind.EXPENSE,EntryKind.REFUND) && assigned(state,e) == b.id
    }.fold(state.frozenBudgetSpends.filter{it.budgetId==b.id&&it.cycleStart==range.first&&it.capturedSequence>b.baselineSequence}.fold(0L){n,it->Money.add(n,it.amountMinor)}) { total,e -> Money.add(total,if(e.kind == EntryKind.REFUND) -e.amountMinor else e.amountMinor) }
    fun allowance(state: LedgerState, b: BudgetEntity, day: Long): Long {
        val target = cycle(b,day) ?: return 0
        var c = b.startDateEpochDay to b.endDateEpochDay
        if (b.period != BudgetPeriod.CUSTOM) c = cycle(b,b.startDateEpochDay)!!
        var amount = b.remainingCents
        if (!b.carryOver && c.first != target.first) return b.totalCents
        while (c.first < target.first) {
            val unused = Money.add(amount,-spent(state,b,c)).coerceAtLeast(0)
            amount = Money.add(b.totalCents, if(b.carryOver) unused else 0)
            c = cycle(b,c.second+1) ?: return 0
        }
        return amount
    }
    fun remaining(state: LedgerState, b: BudgetEntity, day: Long): Long {
        val c = cycle(b,day) ?: return 0
        return Money.add(allowance(state,b,day),-spent(state,b,c)).coerceAtLeast(0)
    }
    fun forecastBudgets(state: LedgerState, today: Long, horizon: Int): List<CoreBudget> = state.budgets.filter { it.isActive }.flatMap { b ->
        (today until today+horizon).mapNotNull { cycle(b,it) }.distinct().map { c ->
            val amount = remaining(state,b,maxOf(today,c.first))
            CoreBudget(b.id,b.name,amount,amount,CoreBudgetPeriod.CUSTOM,c.first,c.second,CoreBudgetMethod.valueOf(b.method.name),b.chargeDay)
        }
    }
}
