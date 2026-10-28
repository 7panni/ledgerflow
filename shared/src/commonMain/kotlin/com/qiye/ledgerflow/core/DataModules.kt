package com.qiye.ledgerflow.core

import com.qiye.ledgerflow.cashflow.data.*

enum class DataModule { BOOK, FLOW }

/** Module operations never persist; the platform repository protects and commits the complete candidate atomically. */
object DataModules {
    fun clearBook(state:LedgerState,today:Long,clearCategories:Boolean=false):LedgerState {
        if(state.entries.isEmpty()&&state.debts.isEmpty()&&state.debtArchives.isEmpty()&&state.importedKeys.isEmpty()&&state.moduleImportKeys.none{it.startsWith("BOOK:")}&&(!clearCategories||state.categories.isEmpty()))return state
        val capture=state.nextSequence
        val spends=state.budgets.flatMap { b->state.entries.filter{it.budgetId==b.id&&it.sequence>b.baselineSequence&&it.affectsBudget&&!it.fundsDetached&&!it.excludeFromBudget&&it.currencyCode=="CNY"&&it.kind in listOf(EntryKind.EXPENSE,EntryKind.REFUND)}.mapNotNull{e->BudgetRules.cycle(b,e.epochDay)?.let{it.first to if(e.kind==EntryKind.REFUND)-e.amountMinor else e.amountMinor}}.groupBy{it.first}.map{(day,items)->FrozenBudgetSpend(b.id,day,items.fold(0L){n,it->Money.add(n,it.second)},capture)} }
        val acknowledgements=state.entries.mapNotNull { e->e.planId?.let{id->SettledOccurrence(id,e.occurrenceEpochDay?:state.plans.first{it.id==id}.dueEpochDay,e.epochDay,e.amountMinor)} }
        val plans=state.plans.map { p->val d=p.debtId?.let{id->state.debts.first{it.id==id}}
            val detached=if(d==null)p else {val left=if(p.isInterest)RichLedger.interestOutstanding(state,d.id)else RichLedger.outstanding(state,d.id);p.copy(debtId=null,active=p.active&&left>0,fundingCapMinor=left.takeIf{it>0},note=p.note+"\n原债务已清账，现为独立${if(p.isInterest)"利息"else "本金"}资金计划")}
            if(clearCategories)detached.copy(categoryId=null)else detached
        }
        var next=state.copy(nextSequence=capture+1,entries=emptyList(),debts=emptyList(),debtArchives=emptyList(),importedKeys=emptyList(),categories=if(clearCategories)emptyList()else state.categories,plans=plans,
            settledOccurrences=(state.settledOccurrences+acknowledgements).distinctBy{it.planId to it.originalEpochDay},frozenBudgetSpends=state.frozenBudgetSpends+spends,moduleImportKeys=state.moduleImportKeys.filterNot{it.startsWith("BOOK:")})
        if(state.entries.any{it.affectsBalance&&!it.fundsDetached&&it.sequence>state.fundingBoundary})next=anchorFunds(state,next,today)
        return LedgerEngine.validate(next)
    }
    private fun anchorFunds(source:LedgerState,target:LedgerState,today:Long):LedgerState {
        val balances=LedgerEngine.balances(source);var next=target
        fun correction(id:String?,amount:Long){val seq=next.nextSequence;next=next.copy(nextSequence=seq+1,corrections=next.corrections+BalanceCorrection("correction-$seq",seq,id,amount,amount,today,0,"模块资金冻结基点"))}
        correction(null,balances.overallMinor)
        source.accounts.filter{it.currencyCode!="CRYPTO"}.forEach{correction(it.id,balances.accountsMinor.getValue(it.id))}
        val holdings=source.accounts.filter{it.currencyCode=="CRYPTO"}.flatMap{a->listOf("USDT","BTC","LTC").map{symbol->CryptoHoldingEntity(symbol,RichLedger.cryptoAccountBalances(source,a.id,symbol),next.nextSequence-1,a.id)}}
        return if(holdings.isNotEmpty())next.copy(holdings=holdings)else next
    }
    fun clearFlow(state:LedgerState):LedgerState {
        val plans=state.plans.associateBy{it.id};val budgets=state.budgets.associateBy{it.id}
        val entries=state.entries.map{e->e.copy(planSnapshot=e.planId?.let{id->"${plans.getValue(id).title} [${id}@${e.occurrenceEpochDay?:plans.getValue(id).dueEpochDay}]"}?:e.planSnapshot,budgetSnapshot=e.budgetId?.let{id->"${budgets.getValue(id).name} [$id]"}?:e.budgetSnapshot,planId=null,budgetId=null)}
        return LedgerEngine.validate(state.copy(nextSequence=state.nextSequence+1,fundingBoundary=state.nextSequence,
            accounts=state.accounts.map{it.copy(openingMinor=0,active=false,includeInForecast=false,archived=true)},entries=entries,plans=emptyList(),budgets=emptyList(),corrections=emptyList(),holdings=emptyList(),occurrenceOverrides=emptyList(),cashflowSources=emptyList(),settledOccurrences=emptyList(),frozenBudgetSpends=emptyList(),
            settings=state.settings.copy(cashflow=LedgerSettings().cashflow),moduleImportKeys=state.moduleImportKeys.filterNot{it.startsWith("FLOW:")}))
    }
    fun snapshot(state:LedgerState,module:DataModule,today:Long):LedgerState {
        if(module==DataModule.FLOW){var result=clearBook(state,today,true);result=anchorFunds(state,result,today);return LedgerEngine.validate(result.copy(categories=emptyList(),settings=result.settings.copy(monthStartDay=1),plans=result.plans.map{it.copy(categoryId=null)},moduleImportKeys=result.moduleImportKeys.filter{it.startsWith("FLOW:")}))}
        val p=state.plans.associateBy{it.id};val b=state.budgets.associateBy{it.id}
        val accountIds=(state.entries.flatMap{listOfNotNull(it.accountId,it.targetAccountId)}+state.debts.mapNotNull{it.accountId}).toSet()
        return LedgerEngine.validate(LedgerState(nextSequence=state.nextSequence,accounts=state.accounts.filter{it.id in accountIds}.map{it.copy(openingMinor=0,active=false,includeInForecast=false,archived=true)},entries=state.entries.map{e->e.copy(fundsDetached=true,planSnapshot=e.planId?.let{"${p.getValue(it).title} [$it@${e.occurrenceEpochDay?:p.getValue(it).dueEpochDay}]"}?:e.planSnapshot,budgetSnapshot=e.budgetId?.let{"${b.getValue(it).name} [$it]"}?:e.budgetSnapshot,planId=null,budgetId=null)},categories=state.categories,debts=state.debts,debtArchives=state.debtArchives,importedKeys=state.importedKeys,settings=LedgerSettings(state.settings.monthStartDay),moduleImportKeys=state.moduleImportKeys.filter{it.startsWith("BOOK:")}))
    }
    fun restore(state:LedgerState,incoming:LedgerState,module:DataModule,today:Long,key:String):LedgerState {
        val receipt="${module.name}:$key";if(receipt in state.moduleImportKeys)return state
        require(incoming==LedgerEngine.validate(incoming))
        if(module==DataModule.BOOK)require(incoming.plans.isEmpty()&&incoming.budgets.isEmpty()&&incoming.corrections.isEmpty()&&incoming.holdings.isEmpty()&&incoming.cashflowSources.isEmpty()){ "记账模块包含越界现金流数据" }
        else require(incoming.entries.isEmpty()&&incoming.debts.isEmpty()&&incoming.debtArchives.isEmpty()&&incoming.categories.isEmpty()&&incoming.importedKeys.isEmpty()){ "现金流模块包含越界记账数据" }
        var next=if(module==DataModule.BOOK)clearBook(state,today,true)else clearFlow(state)
        val points=(incoming.accounts.map{it.sequence}+incoming.entries.map{it.sequence}+incoming.debts.map{it.sequence}+incoming.categories.map{it.sequence}+incoming.plans.map{it.sequence}+incoming.corrections.map{it.sequence}+incoming.budgets.flatMap{listOf(it.id,it.baselineSequence)}+incoming.holdings.map{it.asOfSequence}+incoming.frozenBudgetSpends.map{it.capturedSequence}).filter{it>0}.distinct().sorted()
        var cursor=next.nextSequence;val sequence=points.associateWith{cursor++};next=next.copy(nextSequence=cursor)
        fun seq(old:Long)=if(old==0L)0 else sequence.getValue(old)
        val accountMap=mutableMapOf<String,String>()
        incoming.accounts.forEach { a->val existing=next.accounts.firstOrNull{it.name==a.name&&it.currencyCode==a.currencyCode};var name=a.name;var suffix=1
            if(existing==null)while(next.accounts.any{it.name==name})name="${a.name}（模块${suffix++}）"
            val id=existing?.id?:"account-${seq(a.sequence)}";accountMap[a.id]=id
            val item=if(module==DataModule.BOOK)existing?:a.copy(id=id,sequence=seq(a.sequence),name=name,openingMinor=0,active=false,includeInForecast=false,archived=true)
                else a.copy(id=id,sequence=existing?.sequence?:seq(a.sequence),name=name)
            next=next.copy(accounts=next.accounts.filterNot{it.id==id}+item)
        }
        fun account(id:String?)=id?.let{accountMap.getValue(it)}
        fun entryId(id:String)="entry-${seq(incoming.entries.first{it.id==id}.sequence)}"
        val categoryMap=incoming.categories.associate{it.id to "category-${seq(it.sequence)}"};val debtMap=incoming.debts.associate{it.id to "debt-${seq(it.sequence)}"};val planMap=incoming.plans.associate{it.id to "plan-${seq(it.sequence)}"}
        if(module==DataModule.BOOK){
            val categories=incoming.categories.map{it.copy(id=categoryMap.getValue(it.id),sequence=seq(it.sequence),parentId=it.parentId?.let(categoryMap::getValue))}
            val debts=incoming.debts.map{it.copy(id=debtMap.getValue(it.id),sequence=seq(it.sequence),accountId=account(it.accountId))}
            val entries=incoming.entries.map{it.copy(id=entryId(it.id),sequence=seq(it.sequence),accountId=account(it.accountId),targetAccountId=account(it.targetAccountId),categoryId=it.categoryId?.let(categoryMap::getValue),debtId=it.debtId?.let(debtMap::getValue),refundOfId=it.refundOfId?.let(::entryId),fundsDetached=true,planId=null,budgetId=null)}
            next=next.copy(entries=entries,debts=debts,debtArchives=emptyList(),categories=categories,importedKeys=incoming.importedKeys,settings=next.settings.copy(monthStartDay=incoming.settings.monthStartDay))
            incoming.debts.filter{DebtGroups.isProjectArchived(incoming,it.id)}.forEach{next=DebtGroups.archiveProject(next,debtMap.getValue(it.id))}
        } else {
            next=next.copy(plans=incoming.plans.map{it.copy(id=planMap.getValue(it.id),sequence=seq(it.sequence),accountId=account(it.accountId),categoryId=null,debtId=null,budgetId=it.budgetId?.let(::seq))},budgets=incoming.budgets.map{b->b.copy(id=seq(b.id),baselineSequence=seq(b.baselineSequence),accountName=incoming.accounts.firstOrNull{it.name==b.accountName}?.let{a->next.accounts.first{it.id==accountMap[a.id]}.name}?:b.accountName)},corrections=incoming.corrections.map{it.copy(id="correction-${seq(it.sequence)}",sequence=seq(it.sequence),accountId=account(it.accountId))},holdings=incoming.holdings.map{it.copy(accountId=account(it.accountId),asOfSequence=seq(it.asOfSequence))},occurrenceOverrides=incoming.occurrenceOverrides.map{it.copy(planId=planMap.getValue(it.planId))},settledOccurrences=incoming.settledOccurrences.map{it.copy(planId=planMap.getValue(it.planId))},frozenBudgetSpends=incoming.frozenBudgetSpends.map{it.copy(budgetId=seq(it.budgetId),capturedSequence=seq(it.capturedSequence))},settings=next.settings.copy(cashflow=incoming.settings.cashflow.copy(hiddenAccountIdsCsv=incoming.settings.cashflow.hiddenAccountIdsCsv.split(',').mapNotNull{it.toLongOrNull()}.mapNotNull{old->incoming.accounts.firstOrNull{it.sequence==old}?.let{a->next.accounts.first{it.id==accountMap[a.id]}.sequence}}.joinToString(","))),cashflowSources=incoming.cashflowSources.map{s->s.copy(accountIds=s.accountIds.mapValues{accountMap[it.value]?:it.value},planIds=s.planIds.mapValues{planMap[it.value]?:it.value},budgetIds=s.budgetIds.mapValues{sequence[it.value]?:it.value})})
        }
        return LedgerEngine.validate(next.copy(moduleImportKeys=(next.moduleImportKeys.filterNot{it.startsWith(module.name+":")}+incoming.moduleImportKeys+receipt).distinct()))
    }
}
