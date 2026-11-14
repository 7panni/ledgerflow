package com.qiye.ledgerflow.ui

import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.cashflow.data.AccountType

internal data class QuickAccountChoices(val quick:List<Account>,val other:List<Account>)

internal fun quickAccountChoices(accounts:List<Account>):QuickAccountChoices {
    val available=accounts.filter{it.active&&!it.archived}
    val priorities=listOf("银行" to AccountType.CMB,"微信" to AccountType.WECHAT,"支付宝" to AccountType.ALIPAY)
    val quick=priorities.mapNotNull{(name,type)->available.firstOrNull{it.name==name}?:available.firstOrNull{it.accountType==type}}.distinctBy{it.id}
    val quickIds=quick.map{it.id}.toSet()
    return QuickAccountChoices(quick,available.filter{it.id !in quickIds})
}

internal fun quickAccountForCurrency(account:Account?,currency:String):String? = account?.takeIf {
    it.currencyCode==currency||it.currencyCode=="CRYPTO"&&CurrencyUnits.isCrypto(currency)
}?.id

/** UI submission requirement only; the shared ledger's historical semantics stay intact. */
internal fun requireQuickEntryAccounts(state:LedgerState,draft:EntryDraft) {
    val source=state.accounts.firstOrNull{it.id==draft.accountId&&it.active&&!it.archived}
        ?:error("请先选择一个当前账户")
    require(quickAccountForCurrency(source,draft.currencyCode)!=null){"账户与金额币种不一致，请重新选择账户"}
    if(draft.kind==EntryKind.TRANSFER) {
        val target=state.accounts.firstOrNull{it.id==draft.targetAccountId&&it.active&&!it.archived}
            ?:error("请先选择转入账户")
        require(source.id!=target.id){"转出与转入账户须不同"}
        require(source.currencyCode==target.currencyCode&&quickAccountForCurrency(target,draft.currencyCode)!=null){"转账须选择同币账户"}
    }
}


internal fun orderedQuickEntryAccounts(accounts:List<Account>,savedOrder:List<String>):List<Account> {
    val available=accounts.filter{it.active&&!it.archived}
    val byId=available.associateBy{it.id}
    val common=listOf("微信" to AccountType.WECHAT,"银行" to AccountType.CMB,"支付宝" to AccountType.ALIPAY)
        .mapNotNull{(name,type)->available.firstOrNull{it.name==name}?:available.firstOrNull{it.accountType==type}}.distinctBy{it.id}
    val commonIds=common.map{it.id}.toSet()
    val base=common+available.filter{it.id !in commonIds}
    val saved=savedOrder.mapNotNull{byId[it]}.distinctBy{it.id}
    val savedIds=saved.map{it.id}.toSet()
    return saved+base.filter{it.id !in savedIds}
}

internal fun defaultQuickEntryAccount(state:LedgerState,currency:String):String? {
    val candidates=orderedQuickEntryAccounts(state.accounts,state.settings.defaultAccountOrder)
        .filter{quickAccountForCurrency(it,currency)!=null}
    if(candidates.isEmpty())return null
    val balances=if(CurrencyUnits.isCrypto(currency))emptyMap()else LedgerEngine.balances(state).accountsMinor
    return (candidates.firstOrNull{account->
        (if(CurrencyUnits.isCrypto(currency))RichLedger.cryptoAccountBalances(state,account.id,currency)
        else balances.getValue(account.id))!=0L
    }?:candidates.first()).id
}

internal fun initialQuickEntryAccount(state:LedgerState,existing:Entry?):String? =
    if(existing==null)defaultQuickEntryAccount(state,"CNY")else existing.accountId
