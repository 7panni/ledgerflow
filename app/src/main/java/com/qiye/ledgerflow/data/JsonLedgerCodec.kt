package com.qiye.ledgerflow.data

import com.qiye.ledgerflow.core.*
import com.qiye.ledgerflow.LedgerFlowTiming
import com.qiye.ledgerflow.cashflow.data.*
import com.qiyue.cashflow.shared.CoreRepeatRule
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Version 2 is explicit and integer-only. Version 1 is upgraded in memory without reapplying balances. */
class JsonLedgerCodec : LedgerCodec {
    private fun obj(vararg fields: Pair<String,Any?>)=JSONObject().apply { fields.forEach { (k,v)->put(k,v?:JSONObject.NULL) } }
    private fun list(values:List<JSONObject>)=JSONArray().apply { values.forEach { put(it) } }
    override fun encode(state:LedgerState):String = encodeValidated(LedgerEngine.validate(state))
    internal fun encodeValidated(state:LedgerState):String {
        val c=state.settings.cashflow
        val result=obj("format" to "qiye-ledgerflow","schemaVersion" to 7,"currency" to "CNY","unit" to "minor","nextSequence" to state.nextSequence,
            "settings" to obj("monthStartDay" to state.settings.monthStartDay,"defaultAccountOrder" to JSONArray(state.settings.defaultAccountOrder),"defaultExpenseCategoryId" to state.settings.defaultExpenseCategoryId,"defaultExpenseExcludesBudget" to state.settings.defaultExpenseExcludesBudget,"cashflow" to obj("showUsdOnHome" to c.showUsdOnHome,"showCryptoOnHome" to c.showCryptoOnHome,"showPaypalOnAccounts" to c.showPaypalOnAccounts,"showAlipayOnAccounts" to c.showAlipayOnAccounts,"showCryptoOnAccounts" to c.showCryptoOnAccounts,"usdRateMicros" to c.usdRateMicros,"defaultHorizonDays" to c.defaultHorizonDays,"budgetMonthStartDay" to c.budgetMonthStartDay,"hiddenAccountIdsCsv" to c.hiddenAccountIdsCsv,"hiddenHomeCurrenciesCsv" to c.hiddenHomeCurrenciesCsv,"showUsdValuation" to c.showUsdValuation,"showCryptoValuation" to c.showCryptoValuation,"cryptoPriceMicros" to JSONObject(c.cryptoPriceMicros),"showFinanceTips" to c.showFinanceTips,"financeTipChangesDaily" to c.financeTipChangesDaily,"automaticPosting" to c.automaticPosting,"automaticPostingFromEpochDay" to c.automaticPostingFromEpochDay,"appMode" to c.appMode,"customModulesCsv" to c.customModulesCsv)),
            "accounts" to list(state.accounts.map { obj("id" to it.id,"sequence" to it.sequence,"name" to it.name,"openingMinor" to it.openingMinor,"currencyCode" to it.currencyCode,"accountType" to it.accountType.name,"active" to it.active,"includeInForecast" to it.includeInForecast,"exchangeRateToCnyMicros" to it.exchangeRateToCnyMicros,"archived" to it.archived) }),
            "entries" to list(state.entries.map { obj("id" to it.id,"sequence" to it.sequence,"kind" to it.kind.name,"amountMinor" to it.amountMinor,"epochDay" to it.epochDay,"minuteOfDay" to it.minuteOfDay,"category" to it.category,"accountId" to it.accountId,"targetAccountId" to it.targetAccountId,"note" to it.note,"planId" to it.planId,"affectsBalance" to it.affectsBalance,"second" to it.second,"categoryId" to it.categoryId,"subcategory" to it.subcategory,"tags" to it.tags,"currencyCode" to it.currencyCode,"excludeFromBudget" to it.excludeFromBudget,"excludeFromStatistics" to it.excludeFromStatistics,"affectsBudget" to it.affectsBudget,"debtId" to it.debtId,"isInterest" to it.isInterest,"refundOfId" to it.refundOfId,"occurrenceEpochDay" to it.occurrenceEpochDay,"importKey" to it.importKey,"sourceDetails" to it.sourceDetails,"budgetId" to it.budgetId,"settlementId" to it.settlementId,"fundsDetached" to it.fundsDetached,"planSnapshot" to it.planSnapshot,"budgetSnapshot" to it.budgetSnapshot,"debtCounterparty" to it.debtCounterparty) }),
            "plans" to list(state.plans.map { obj("id" to it.id,"sequence" to it.sequence,"title" to it.title,"kind" to it.kind.name,"amountMinor" to it.amountMinor,"dueEpochDay" to it.dueEpochDay,"category" to it.category,"accountId" to it.accountId,"note" to it.note,"currencyCode" to it.currencyCode,"repeatRule" to it.repeatRule.name,"endEpochDay" to it.endEpochDay,"installmentTotal" to it.installmentTotal,"installmentRemaining" to it.installmentRemaining,"installmentTotalAmountMinor" to it.installmentTotalAmountMinor,"active" to it.active,"temporary" to it.temporary,"usesBudget" to it.usesBudget,"categoryId" to it.categoryId,"subcategory" to it.subcategory,"debtId" to it.debtId,"isInterest" to it.isInterest,"budgetId" to it.budgetId,"postingFromEpochDay" to it.postingFromEpochDay,"notBeforeEpochDay" to it.notBeforeEpochDay,"fundingCapMinor" to it.fundingCapMinor) }),
            "corrections" to list(state.corrections.map { obj("id" to it.id,"sequence" to it.sequence,"accountId" to it.accountId,"beforeMinor" to it.beforeMinor,"afterMinor" to it.afterMinor,"epochDay" to it.epochDay,"minuteOfDay" to it.minuteOfDay,"reason" to it.reason) }),
            "categories" to list(state.categories.map { obj("id" to it.id,"sequence" to it.sequence,"name" to it.name,"kind" to it.kind.name,"parentId" to it.parentId,"order" to it.order,"active" to it.active) }),
            "debts" to list(state.debts.map { obj("id" to it.id,"sequence" to it.sequence,"kind" to it.kind.name,"person" to it.person,"principalMinor" to it.principalMinor,"openedEpochDay" to it.openedEpochDay,"accountId" to it.accountId,"currencyCode" to it.currencyCode,"dueEpochDay" to it.dueEpochDay,"interestMinor" to it.interestMinor,"note" to it.note,"interestProvided" to it.interestProvided) }),
            "budgets" to list(state.budgets.map { obj("id" to it.id,"name" to it.name,"totalCents" to it.totalCents,"remainingCents" to it.remainingCents,"period" to it.period.name,"startDateEpochDay" to it.startDateEpochDay,"endDateEpochDay" to it.endDateEpochDay,"accountName" to it.accountName,"method" to it.method.name,"chargeDay" to it.chargeDay,"carryOver" to it.carryOver,"active" to it.isActive,"baselineSequence" to it.baselineSequence,"monthStartDay" to it.monthStartDay) }),
            "holdings" to list(state.holdings.map { obj("symbol" to it.symbol,"amountUnits" to it.amountUnits,"asOfSequence" to it.asOfSequence,"accountId" to it.accountId) }),
            "occurrenceOverrides" to list(state.occurrenceOverrides.map { obj("planId" to it.planId,"originalEpochDay" to it.originalEpochDay,"dueEpochDay" to it.dueEpochDay) }),
            "cashflowSources" to list(state.cashflowSources.map { source->obj("key" to source.key,"importedEpochDay" to source.importedEpochDay,"accountIds" to JSONObject(source.accountIds.mapKeys { it.key.toString() }),"planIds" to JSONObject(source.planIds.mapKeys { it.key.toString() }),"budgetIds" to JSONObject(source.budgetIds.mapKeys { it.key.toString() }),"originalJson" to source.originalJson) }),
            "settledOccurrences" to list(state.settledOccurrences.map{obj("planId" to it.planId,"originalEpochDay" to it.originalEpochDay,"actualEpochDay" to it.actualEpochDay,"amountMinor" to it.amountMinor)}),
            "frozenBudgetSpends" to list(state.frozenBudgetSpends.map{obj("budgetId" to it.budgetId,"cycleStart" to it.cycleStart,"amountMinor" to it.amountMinor,"capturedSequence" to it.capturedSequence)}),
            "fundingBoundary" to state.fundingBoundary,"moduleImportKeys" to JSONArray(state.moduleImportKeys),
            "debtArchives" to list(state.debtArchives.map{obj("debtIds" to JSONArray(it.debtIds),"entryIds" to JSONArray(it.entryIds),"contentStamp" to it.contentStamp)}),
            "importedKeys" to JSONArray(state.importedKeys)
        ).toString(2)
        require(result.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "账本超过64MB备份上限" }
        return result
    }
    override fun decode(text:String):LedgerState = decodeWithVersion(text).first
    internal fun decodeWithVersion(text:String):Pair<LedgerState,Int> {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "备份超过64MB，未读取" }
        val token=JSONTokener(text);val root=LedgerFlowTiming.measure("json_parse"){token.nextValue() as? JSONObject?:error("备份根须为JSON对象")}
        require(token.nextClean()=='\u0000') { "备份末尾有损坏内容" }
        require(root.text("format")=="qiye-ledgerflow") { "不是本应用的全量备份" }
        val version=root.number("schemaVersion");require(version in 1..7) { "不支持的备份版本，请保留文件并使用匹配版本" }
        require(root.text("currency")=="CNY" && root.text("unit")=="minor") { "币种/单位无效" }
        val mappingStarted=System.nanoTime()
        val s=root.getJSONObject("settings"); val cf=if(version>=2L)s.getJSONObject("cashflow") else JSONObject()
        val settings=LedgerSettings(s.integer("monthStartDay"),AppSettingsEntity(showUsdOnHome=cf.boolOr("showUsdOnHome",true),showCryptoOnHome=cf.boolOr("showCryptoOnHome",true),showPaypalOnAccounts=cf.boolOr("showPaypalOnAccounts",true),showAlipayOnAccounts=cf.boolOr("showAlipayOnAccounts",true),showCryptoOnAccounts=cf.boolOr("showCryptoOnAccounts",true),usdRateMicros=cf.longOr("usdRateMicros",7_200_000),defaultHorizonDays=cf.intOr("defaultHorizonDays",90),budgetMonthStartDay=cf.intOr("budgetMonthStartDay",1),hiddenAccountIdsCsv=cf.stringOr("hiddenAccountIdsCsv"),hiddenHomeCurrenciesCsv=cf.stringOr("hiddenHomeCurrenciesCsv"),showFinanceTips=cf.boolOr("showFinanceTips",true),financeTipChangesDaily=cf.boolOr("financeTipChangesDaily",false),onboardingCompleted=true,seeded=true,automaticPosting=cf.boolOr("automaticPosting",false),automaticPostingFromEpochDay=cf.nullableNumber("automaticPostingFromEpochDay"),appMode=cf.stringOr("appMode","FULL"),customModulesCsv=cf.stringOr("customModulesCsv"),showUsdValuation=cf.boolOr("showUsdValuation",false),showCryptoValuation=cf.boolOr("showCryptoValuation",false),cryptoPriceMicros=if(cf.has("cryptoPriceMicros"))cf.getJSONObject("cryptoPriceMicros").let{o->o.keys().asSequence().associateWith{o.number(it)}}else emptyMap()),defaultAccountOrder=s.accountOrder(),
            defaultExpenseCategoryId=(s.opt("defaultExpenseCategoryId") as? String)?.takeIf { it.isNotBlank() },
            defaultExpenseExcludesBudget=s.opt("defaultExpenseExcludesBudget") == true)
        fun objects(key:String)=if(version==1L && !root.has(key))emptyList() else root.objects(key)
        val state=LedgerState(nextSequence=root.number("nextSequence"),settings=settings,
            accounts=root.objects("accounts").map { Account(it.text("id"),it.number("sequence"),it.text("name"),it.number("openingMinor"),it.stringOr("currencyCode","CNY"),enumValueOf(it.stringOr("accountType","CUSTOM")),it.boolOr("active",true),it.boolOr("includeInForecast",true),it.longOr("exchangeRateToCnyMicros",1_000_000),it.boolOr("archived",false)) },
            entries=root.objects("entries").map { Entry(it.text("id"),it.number("sequence"),enumValueOf(it.text("kind")),it.number("amountMinor"),it.number("epochDay"),it.integer("minuteOfDay"),it.text("category"),it.nullableText("accountId"),it.nullableText("targetAccountId"),it.text("note"),it.nullableText("planId"),it.flag("affectsBalance"),it.intOr("second",0),it.nullableText("categoryId"),it.stringOr("subcategory"),it.stringOr("tags"),it.stringOr("currencyCode","CNY"),it.boolOr("excludeFromBudget",false),it.boolOr("excludeFromStatistics",false),it.boolOr("affectsBudget",true),it.nullableText("debtId"),it.boolOr("isInterest",false),it.nullableText("refundOfId"),it.nullableNumber("occurrenceEpochDay"),it.nullableText("importKey"),it.stringOr("sourceDetails"),it.nullableNumber("budgetId"),it.nullableText("settlementId"),it.boolOr("fundsDetached",false),it.stringOr("planSnapshot"),it.stringOr("budgetSnapshot"),it.stringOr("debtCounterparty")) },
            plans=root.objects("plans").map { Plan(it.text("id"),it.number("sequence"),it.text("title"),enumValueOf(it.text("kind")),it.number("amountMinor"),it.number("dueEpochDay"),it.text("category"),it.nullableText("accountId"),it.text("note"),it.stringOr("currencyCode","CNY"),enumValueOf(it.stringOr("repeatRule","NONE")),it.nullableNumber("endEpochDay"),it.intOr("installmentTotal",1),it.intOr("installmentRemaining",0),it.longOr("installmentTotalAmountMinor",0),it.boolOr("active",true),it.boolOr("temporary",false),it.boolOr("usesBudget",false),it.nullableText("categoryId"),it.stringOr("subcategory"),it.nullableText("debtId"),it.boolOr("isInterest",false),it.nullableNumber("budgetId"),it.nullableNumber("postingFromEpochDay"),it.nullableNumber("notBeforeEpochDay"),it.nullableNumber("fundingCapMinor")) },
            corrections=root.objects("corrections").map { BalanceCorrection(it.text("id"),it.number("sequence"),it.nullableText("accountId"),it.number("beforeMinor"),it.number("afterMinor"),it.number("epochDay"),it.integer("minuteOfDay"),it.text("reason")) },
            categories=objects("categories").map { LedgerCategory(it.text("id"),it.number("sequence"),it.text("name"),enumValueOf(it.text("kind")),it.nullableText("parentId"),it.integer("order"),it.flag("active")) },
            debts=objects("debts").map { Debt(it.text("id"),it.number("sequence"),enumValueOf(it.text("kind")),it.text("person"),it.number("principalMinor"),it.number("openedEpochDay"),it.nullableText("accountId"),it.text("currencyCode"),it.nullableNumber("dueEpochDay"),it.number("interestMinor"),it.text("note"),it.boolOr("interestProvided",true)) },
            budgets=objects("budgets").map { BudgetEntity(id=it.number("id"),name=it.text("name"),totalCents=it.number("totalCents"),remainingCents=it.number("remainingCents"),period=enumValueOf(it.text("period")),startDateEpochDay=it.number("startDateEpochDay"),endDateEpochDay=it.number("endDateEpochDay"),accountName=it.text("accountName"),method=enumValueOf(it.text("method")),chargeDay=it.nullableNumber("chargeDay")?.toInt(),carryOver=it.flag("carryOver"),isActive=it.flag("active"),baselineSequence=it.number("baselineSequence"),monthStartDay=it.nullableNumber("monthStartDay")?.toInt()) },
            holdings=objects("holdings").map { CryptoHoldingEntity(it.text("symbol"),it.number("amountUnits"),it.number("asOfSequence"),it.nullableText("accountId")) },
            occurrenceOverrides=objects("occurrenceOverrides").map { OccurrenceOverride(it.text("planId"),it.number("originalEpochDay"),it.number("dueEpochDay")) },
            cashflowSources=if(version<3L)emptyList() else root.objects("cashflowSources").map { source->
                fun strings(key:String)=source.getJSONObject(key).let { o->o.keys().asSequence().associate { k->k.toLong() to o.getString(k) } }
                val budgets=source.getJSONObject("budgetIds").let { o->o.keys().asSequence().associate { k->k.toLong() to o.number(k) } }
                CashflowSource(source.text("key"),source.number("importedEpochDay"),strings("accountIds"),strings("planIds"),budgets,source.text("originalJson")) },
            settledOccurrences=if(version<4L)emptyList() else root.objects("settledOccurrences").map{SettledOccurrence(it.text("planId"),it.number("originalEpochDay"),it.number("actualEpochDay"),it.number("amountMinor"))},
            frozenBudgetSpends=if(version<4L)emptyList() else root.objects("frozenBudgetSpends").map{FrozenBudgetSpend(it.number("budgetId"),it.number("cycleStart"),it.number("amountMinor"),it.number("capturedSequence"))},
            fundingBoundary=if(version<4L)0 else root.number("fundingBoundary"),moduleImportKeys=if(version<4L)emptyList() else root.getJSONArray("moduleImportKeys").let{a->require(a.length()<=100_000);(0 until a.length()).map{a.getString(it)}},
            debtArchives=if(version<6L)emptyList()else root.objects("debtArchives").map{DebtArchive(it.strings("debtIds"),it.strings("entryIds"),it.stringOr("contentStamp"))},
            importedKeys=if(version==1L)emptyList() else root.getJSONArray("importedKeys").let { a->require(a.length()<=100_000);(0 until a.length()).map { a.get(it) as? String?:error("导入身份须为文本") } })
        LedgerFlowTiming.mark("json_mapping",state.entries.size.toLong(),(System.nanoTime()-mappingStarted)/1_000_000)
        return LedgerFlowTiming.measure("validate",state.entries.size.toLong()){if(version==1L)RichLedger.upgradeV1(state) else LedgerEngine.validate(state)} to version.toInt()
    }
    private fun JSONObject.accountOrder():List<String> {
        val values=optJSONArray("defaultAccountOrder")?:return emptyList()
        return (0 until minOf(values.length(),100_000)).mapNotNull{values.opt(it) as? String}
            .filter{it.isNotBlank()&&it.length<=100}.distinct()
    }
    private fun JSONObject.strings(key:String):List<String>{val a=getJSONArray(key);require(a.length()<=100000);return (0 until a.length()).map{a.getString(it)}}
    private fun JSONObject.text(key:String):String=(get(key) as? String)?:error("字段${key}须为文本")
    private fun JSONObject.nullableText(key:String):String?=if(!has(key)||isNull(key))null else text(key)
    private fun JSONObject.stringOr(key:String,default:String="")=if(has(key))text(key) else default
    private fun JSONObject.number(key:String):Long {
        return when(val value=get(key)) {
            is Long -> value
            is Int -> value.toLong()
            else -> {
                require(value is Number){"字段${key}须为精确整数"}
                val integer=value.toString()
                require(EXACT_INTEGER.matches(integer)){"字段${key}须为精确整数"}
                integer.toLongOrNull()?:error("字段${key}溢出")
            }
        }
    }
    private fun JSONObject.integer(key:String):Int { val value=number(key);require(value in Int.MIN_VALUE..Int.MAX_VALUE);return value.toInt() }
    private fun JSONObject.longOr(key:String,default:Long)=if(has(key))number(key) else default
    private fun JSONObject.intOr(key:String,default:Int)=if(has(key))integer(key) else default
    private fun JSONObject.nullableNumber(key:String)=if(!has(key)||isNull(key))null else number(key)
    private fun JSONObject.flag(key:String):Boolean=(get(key) as? Boolean)?:error("字段${key}须为布尔值")
    private fun JSONObject.boolOr(key:String,default:Boolean)=if(has(key))flag(key) else default
    private fun JSONObject.objects(key:String):List<JSONObject> { val a=getJSONArray(key);require(a.length()<=100_000);return (0 until a.length()).map { a.getJSONObject(it) } }
    companion object { const val MAX_BYTES=64*1024*1024; private val EXACT_INTEGER=Regex("-?\\d+") }
}
