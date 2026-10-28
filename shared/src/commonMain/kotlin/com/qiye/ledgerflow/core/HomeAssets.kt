package com.qiye.ledgerflow.core

data class HomeAssetSummary(val accountIds:List<String>,val cnyMinor:Long,val usdMinor:Long,val cryptoUnits:Map<String,Long>,val otherMinor:Map<String,Long>,val totalCnyMinor:Long?,val missingRates:List<String>,val hasUsd:Boolean,val hasCrypto:Boolean)

/** Display-only valuation. It never writes or substitutes the independent cashflow baseline. */
object HomeAssets {
    fun visibleAccounts(state:LedgerState):List<Account>{
        val c=state.settings.cashflow;val hiddenIds=c.hiddenAccountIdsCsv.split(',').mapNotNull{it.trim().toLongOrNull()}.toSet()
        val hiddenCurrencies=c.hiddenHomeCurrenciesCsv.split(',').map{it.trim()}.toSet()
        return state.accounts.filter{it.active&&!it.archived&&it.sequence !in hiddenIds&&it.currencyCode !in hiddenCurrencies}
    }
    fun calculate(state:LedgerState):HomeAssetSummary {
        val accounts=visibleAccounts(state);val balances=LedgerEngine.balances(state).accountsMinor;val c=state.settings.cashflow
        fun sum(code:String)=accounts.filter{it.currencyCode==code}.fold(0L){n,a->Money.add(n,balances.getValue(a.id))}
        val cny=sum("CNY");val usd=sum("USD");val crypto=AssetValuation.symbols.associateWith{symbol->accounts.filter{it.currencyCode=="CRYPTO"}.fold(0L){n,a->Money.add(n,RichLedger.cryptoAccountBalances(state,a.id,symbol))}}
        val others=accounts.filter{it.currencyCode !in listOf("CNY","USD","CRYPTO")}.groupBy{it.currencyCode}.mapValues{(_,list)->list.fold(0L){n,a->Money.add(n,balances.getValue(a.id))}}
        var total=cny;val missing=mutableListOf<String>()
        if(usd!=0L){if(c.usdRateMicros<=0)missing+="USD" else total=Money.add(total,AssetValuation.amount(usd,c.usdRateMicros,1_000_000))}
        crypto.forEach{(symbol,units)->if(units!=0L){val price=c.cryptoPriceMicros[symbol];if(price==null)missing+=symbol else total=Money.add(total,AssetValuation.amount(units,price,1_000_000_000_000))}}
        accounts.filter{it.currencyCode !in listOf("CNY","USD","CRYPTO")}.forEach{a->val units=balances.getValue(a.id);if(units!=0L){if(a.exchangeRateToCnyMicros<=0)missing+=a.currencyCode else total=Money.add(total,AssetValuation.amount(units,a.exchangeRateToCnyMicros,1_000_000))}}
        return HomeAssetSummary(accounts.map{it.id},cny,usd,crypto,others,if(missing.isEmpty())total else null,missing.distinct(),accounts.any{it.currencyCode=="USD"},accounts.any{it.currencyCode=="CRYPTO"})
    }
}
