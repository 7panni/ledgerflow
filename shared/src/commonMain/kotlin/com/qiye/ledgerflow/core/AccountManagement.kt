package com.qiye.ledgerflow.core

import com.qiye.ledgerflow.cashflow.data.AccountType

object AccountManagement {
    fun selectable(a:Account)=a.active&&!a.archived
    fun historicalBanks(state:LedgerState):List<Account>{
        val cashflowIds=state.cashflowSources.flatMap{it.accountIds.values}.toSet()
        val balances=LedgerEngine.balances(state)
        val words=listOf("银行","招商","中行","建行","工行","农行","交行","邮储","公积金")
        return state.accounts.filter{a->
            val refs=state.entries.filter{it.accountId==a.id||it.targetAccountId==a.id}
            selectable(a)&&a.name!="银行"&&a.currencyCode=="CNY"&&a.accountType in listOf(AccountType.CUSTOM,AccountType.CMB)&&a.id !in cashflowIds&&words.any{a.name.contains(it)}&&a.openingMinor==0L&&balances.accountsMinor.getValue(a.id)==0L&&state.corrections.none{it.accountId==a.id}&&state.plans.none{it.accountId==a.id}&&refs.isNotEmpty()&&refs.all{it.importKey!=null&&(!it.affectsBalance||it.fundsDetached||it.sequence<=state.fundingBoundary)}
        }
    }
    fun retire(state:LedgerState,id:String,today:Long,replacement:String?=null):LedgerState {
        val a=state.accounts.firstOrNull{it.id==id}?:error("账户不存在")
        if(a.archived)return state
        val plans=state.plans.filter{it.accountId==id}
        val target=replacement?.let{k->state.accounts.firstOrNull{it.id==k&&it.id!=id&&selectable(it)&&it.currencyCode==a.currencyCode}?:error("请选择同币种的有效账户")}
        if(a.currencyCode!="CNY"&&plans.isNotEmpty())require(target!=null){"此账户有关联计划，请选择同币种账户接续"}
        var next=state
        if(a.currencyCode!="CRYPTO")next=LedgerEngine.correctBalance(next,id,LedgerEngine.balances(state).accountsMinor.getValue(id),today,0,"移除账户前余额留存")
        val destination=target?.id
        next=next.copy(accounts=next.accounts.map{if(it.id==id)it.copy(active=false,includeInForecast=false,archived=true)else it},
            plans=next.plans.map{if(it.accountId==id)it.copy(accountId=destination,note=(it.note+"\n原账户：${a.name}").trim().take(4000))else it},
            debts=next.debts.map{if(it.accountId==id)it.copy(accountId=destination)else it},
            budgets=next.budgets.map{if(it.accountName==a.name)it.copy(accountName=target?.name.orEmpty())else it})
        return LedgerEngine.validate(next)
    }
    fun cleanHistoricalBanks(state:LedgerState,today:Long):LedgerState{var next=state;historicalBanks(state).forEach{next=retire(next,it.id,today)};return next}
}
