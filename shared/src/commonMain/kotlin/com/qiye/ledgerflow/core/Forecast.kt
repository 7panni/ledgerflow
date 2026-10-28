package com.qiye.ledgerflow.core

import com.qiyue.cashflow.shared.*
import com.qiye.ledgerflow.cashflow.data.*

/** Reuses the user's forecast engine; CNY has one independent synthetic balance. */
object LedgerForecast {
    fun forecast(state: LedgerState, today: Long, horizon: Int): CoreForecastSummary {
        require(horizon in 1..366) { "预测区间须为1至366天" }
        val balances = LedgerEngine.balances(state)
        val accounts = listOf(CoreAccount("@overall",balances.overallMinor,"CNY")) + state.accounts.filter { it.currencyCode != "CNY" && it.currencyCode != "CRYPTO" }.map { CoreAccount(it.id,balances.accountsMinor.getValue(it.id),it.currencyCode,included=it.includeInForecast,active=it.active) }
        val occurrences = RichLedger.pending(state,today+horizon-1).filter { o->o.plan.accountId?.let{id->state.accounts.first{it.id==id}.let{it.active&&it.includeInForecast}}?:true }
        val lookup = occurrences.associateBy { it.key }
        val adjustments = occurrences.map { o ->
            val p=o.plan
            val amount=if(p.debtId!=null) minOf(o.amountMinor,if(p.isInterest)RichLedger.interestOutstanding(state,p.debtId) else RichLedger.outstanding(state,p.debtId)) else o.amountMinor
            CoreAdjustment(o.key,amount,if(p.currencyCode=="CNY")"@overall" else p.accountId?:"@${p.currencyCode}", maxOf(today,o.dueEpochDay),!p.kind.isOutflow(),false)
        }
        val coreBudgets=state.budgets.filter { it.isActive }.flatMap { budget ->
            val cycles=(today until today+horizon).mapNotNull { BudgetRules.cycle(budget,it) }.distinct().sortedBy { it.first }
            var carry=0L
            cycles.mapIndexed { index,cycle ->
                val base=if(index==0 && today>=cycle.first)BudgetRules.remaining(state,budget,today)
                    else if(index==0 && cycle.first==budget.startDateEpochDay)budget.remainingCents
                    else Money.add(budget.totalCents,if(budget.carryOver)carry else 0)
                val planned=occurrences.filter { o -> o.plan.usesBudget && o.plan.kind.isOutflow() && o.plan.currencyCode=="CNY" && maxOf(today,o.dueEpochDay) in cycle.first..cycle.second && (o.plan.budgetId ?: state.budgets.filter { it.isActive && BudgetRules.cycle(it,maxOf(today,o.dueEpochDay))!=null }.singleOrNull()?.id)==budget.id }.fold(0L) { total,o -> Money.add(total,o.amountMinor) }
                val amount=Money.add(base,-planned).coerceAtLeast(0)
                val hasCharge=budget.method==BudgetMethod.DAILY_AVERAGE || (maxOf(today,cycle.first)..cycle.second).any { day -> val d=CivilDate.fromEpochDay(day);d.day==minOf(budget.chargeDay?:CivilDate.fromEpochDay(cycle.second).day,d.lengthOfMonth()) }
                carry=if(hasCharge)0 else amount
                CoreBudget(budget.id,budget.name,amount,amount,CoreBudgetPeriod.CUSTOM,cycle.first,cycle.second,CoreBudgetMethod.valueOf(budget.method.name),budget.chargeDay)
            }
        }
        // Bound all additions before invoking the original engine's arithmetic.
        var low=balances.overallMinor; var high=balances.overallMinor
        occurrences.filter { it.plan.currencyCode=="CNY" }.forEach { o -> if(o.plan.kind.isOutflow()) low=Money.add(low,-o.amountMinor) else high=Money.add(high,o.amountMinor) }
        coreBudgets.forEach { low=Money.add(low,-it.remainingMinor) }
        val result=CoreForecastEngine().forecast(accounts,emptyList(),coreBudgets,adjustments,today,horizon)
        val remaining=state.budgets.filter { it.isActive }.fold(0L) { amount,b -> Money.add(amount,BudgetRules.remaining(state,b,today)) }
        return result.copy(budgetRemainingMinor=remaining,days=result.days.map { d ->
            Money.checked(d.cnyBalanceMinor); Money.checked(d.usdBalanceMinor)
            d.copy(events=d.events.map { e -> lookup[e.name]?.let { o -> e.copy(name=o.plan.title,accountName=o.plan.accountId?.let { id->state.accounts.firstOrNull { it.id==id }?.name }?:"无账户",currencyCode=o.plan.currencyCode,kind=if(o.plan.installmentTotal>1)"分期付款" else if(o.plan.temporary)"临时收支" else "计划") }?:e })
        })
    }
}
