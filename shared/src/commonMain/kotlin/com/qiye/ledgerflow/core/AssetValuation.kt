package com.qiye.ledgerflow.core

object AssetValuation {
    val symbols=listOf("USDT","BTC","LTC")
    /** Rounded integer product/division without multiplying two large Long operands. */
    fun amount(units:Long,priceMicros:Long,divisor:Long):Long {
        Money.checked(units);require(priceMicros in 0..Money.LIMIT&&divisor in 1..1_000_000_000_000L){"汇率超出范围"}
        val a=if(units<0)-units else units;val aq=a/divisor;val ar=a%divisor
        var q=0L;var rem=0L
        for(bit in 62 downTo 0){
            q=Money.add(q,q);rem*=2
            if(((priceMicros ushr bit)and 1L)!=0L){q=Money.add(q,aq);rem+=ar}
            q=Money.add(q,rem/divisor);rem%=divisor
        }
        if(rem*2>=divisor)q=Money.add(q,1)
        return if(units<0)-q else q
    }
    fun usd(state:LedgerState,rate:Long=state.settings.cashflow.usdRateMicros):Long {
        val b=LedgerEngine.balances(state);val units=state.accounts.filter{AccountManagement.selectable(it)&&it.currencyCode=="USD"}.fold(0L){n,a->Money.add(n,b.accountsMinor.getValue(a.id))}
        return amount(units,rate,1_000_000)
    }
    fun crypto(state:LedgerState,prices:Map<String,Long> = state.settings.cashflow.cryptoPriceMicros):Long?{
        val holdings=RichLedger.cryptoBalances(state);var total=0L;var missing=false
        symbols.forEach{s->val units=holdings.getValue(s);if(units!=0L){val price=prices[s];if(price==null)missing=true else total=Money.add(total,amount(units,price,1_000_000_000_000))}}
        return if(missing)null else total
    }
    fun configure(state:LedgerState,usdRate:Long,prices:Map<String,Long>,showUsd:Boolean,showCrypto:Boolean):LedgerState {
        require(usdRate in 1..1_000_000_000){"美元汇率须大于0且不超过1000"}
        require(prices.keys.all{it in symbols}&&prices.values.all{it in 0..Money.LIMIT}){"币价须为非负数且在支持范围内"}
        usd(state,usdRate);crypto(state,prices)
        val old=state.settings.cashflow
        val hidden=old.hiddenHomeCurrenciesCsv.split(',').map{it.trim()}.filter{it.isNotEmpty()}.toMutableSet()
        if(showUsd)hidden.remove("USD")else hidden.add("USD");if(showCrypto)hidden.remove("CRYPTO")else hidden.add("CRYPTO")
        return LedgerEngine.validate(state.copy(settings=state.settings.copy(cashflow=state.settings.cashflow.copy(usdRateMicros=usdRate,cryptoPriceMicros=prices,showUsdValuation=showUsd,showCryptoValuation=showCrypto,hiddenHomeCurrenciesCsv=hidden.sorted().joinToString(","),showUsdOnHome=showUsd,showCryptoOnHome=showCrypto))))
    }
}
